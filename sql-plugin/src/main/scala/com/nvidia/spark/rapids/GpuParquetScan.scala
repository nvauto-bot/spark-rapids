/*
 * Copyright (c) 2019-2020, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nvidia.spark.rapids

import java.io.OutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.{Collections, Locale}
import java.util.concurrent._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable.{ArrayBuffer, Queue}
import scala.math.max

import ai.rapids.cudf._
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.nvidia.spark.RebaseHelper
import com.nvidia.spark.rapids.GpuMetricNames._
import com.nvidia.spark.rapids.ParquetPartitionReader.CopyRange
import com.nvidia.spark.rapids.RapidsPluginImplicits._
import org.apache.commons.io.IOUtils
import org.apache.commons.io.output.{CountingOutputStream, NullOutputStream}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FSDataInputStream, Path}
import org.apache.parquet.bytes.BytesUtils
import org.apache.parquet.column.ColumnDescriptor
import org.apache.parquet.filter2.predicate.FilterApi
import org.apache.parquet.format.converter.ParquetMetadataConverter
import org.apache.parquet.hadoop.{ParquetFileReader, ParquetInputFormat}
import org.apache.parquet.hadoop.metadata._
import org.apache.parquet.schema.{GroupType, MessageType, Types}

import org.apache.spark.TaskContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.connector.read.{InputPartition, PartitionReader, PartitionReaderFactory}
import org.apache.spark.sql.execution.QueryExecutionException
import org.apache.spark.sql.execution.datasources.{FilePartition, PartitionedFile}
import org.apache.spark.sql.execution.datasources.parquet.{ParquetFilters, ParquetReadSupport}
import org.apache.spark.sql.execution.datasources.v2.FilePartitionReaderFactory
import org.apache.spark.sql.execution.datasources.v2.parquet.ParquetScan
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.rapids.InputFileUtils
import org.apache.spark.sql.rapids.execution.TrampolineUtil
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types.{StringType, StructType, TimestampType}
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.util.SerializableConfiguration

abstract class GpuParquetScanBase(
    sparkSession: SparkSession,
    hadoopConf: Configuration,
    dataSchema: StructType,
    readDataSchema: StructType,
    readPartitionSchema: StructType,
    pushedFilters: Array[Filter],
    rapidsConf: RapidsConf,
    supportsSmallFileOpt: Boolean)
  extends ScanWithMetrics with Logging {

  def isSplitableBase(path: Path): Boolean = true

  def createReaderFactoryBase(): PartitionReaderFactory = {
    val broadcastedConf = sparkSession.sparkContext.broadcast(
      new SerializableConfiguration(hadoopConf))

    logDebug(s"Small file optimization support: $supportsSmallFileOpt")
    if (supportsSmallFileOpt) {
      GpuParquetMultiFilePartitionReaderFactory(sparkSession.sessionState.conf, broadcastedConf,
        dataSchema, readDataSchema, readPartitionSchema, pushedFilters, rapidsConf, metrics)
    } else {
      GpuParquetPartitionReaderFactory(sparkSession.sessionState.conf, broadcastedConf,
        dataSchema, readDataSchema, readPartitionSchema, pushedFilters, rapidsConf, metrics)
    }
  }
}

object GpuParquetScanBase {
  def tagSupport(scanMeta: ScanMeta[ParquetScan]): Unit = {
    val scan = scanMeta.wrapped
    val schema = StructType(scan.readDataSchema ++ scan.readPartitionSchema)
    tagSupport(scan.sparkSession, schema, scanMeta)
  }

  def tagSupport(
      sparkSession: SparkSession,
      readSchema: StructType,
      meta: RapidsMeta[_, _, _]): Unit = {
    val sqlConf = sparkSession.conf

    if (!meta.conf.isParquetEnabled) {
      meta.willNotWorkOnGpu("Parquet input and output has been disabled. To enable set" +
        s"${RapidsConf.ENABLE_PARQUET} to true")
    }

    if (!meta.conf.isParquetReadEnabled) {
      meta.willNotWorkOnGpu("Parquet input has been disabled. To enable set" +
        s"${RapidsConf.ENABLE_PARQUET_READ} to true")
    }

    for (field <- readSchema) {
      if (!GpuColumnVector.isSupportedType(field.dataType)) {
        meta.willNotWorkOnGpu(s"GpuParquetScan does not support fields of type ${field.dataType}")
      }
    }

    val schemaHasStrings = readSchema.exists { field =>
      TrampolineUtil.dataTypeExistsRecursively(field.dataType, _.isInstanceOf[StringType])
    }

    if (sqlConf.get(SQLConf.PARQUET_BINARY_AS_STRING.key,
      SQLConf.PARQUET_BINARY_AS_STRING.defaultValueString).toBoolean && schemaHasStrings) {
      meta.willNotWorkOnGpu(s"GpuParquetScan does not support" +
          s" ${SQLConf.PARQUET_BINARY_AS_STRING.key}")
    }

    val schemaHasTimestamps = readSchema.exists { field =>
      TrampolineUtil.dataTypeExistsRecursively(field.dataType, _.isInstanceOf[TimestampType])
    }

    // Currently timestamp conversion is not supported.
    // If support needs to be added then we need to follow the logic in Spark's
    // ParquetPartitionReaderFactory and VectorizedColumnReader which essentially
    // does the following:
    //   - check if Parquet file was created by "parquet-mr"
    //   - if not then look at SQLConf.SESSION_LOCAL_TIMEZONE and assume timestamps
    //     were written in that timezone and convert them to UTC timestamps.
    // Essentially this should boil down to a vector subtract of the scalar delta
    // between the configured timezone's delta from UTC on the timestamp data.
    if (schemaHasTimestamps && sparkSession.sessionState.conf.isParquetINT96TimestampConversion) {
      meta.willNotWorkOnGpu("GpuParquetScan does not support int96 timestamp conversion")
    }

    sqlConf.get(SQLConf.LEGACY_PARQUET_REBASE_MODE_IN_READ.key) match {
      case "EXCEPTION" => // Good
      case "CORRECTED" => // Good
      case "LEGACY" => // Good, but it really is EXCEPTION for us...
      case other =>
        meta.willNotWorkOnGpu(s"$other is not a supported read rebase mode")
    }
  }
}

/**
 * Base object that has common functions for both GpuParquetPartitionReaderFactory
 * and GpuParquetPartitionReaderFactory
 */
object GpuParquetPartitionReaderFactoryBase {

  def filterClippedSchema(
      clippedSchema: MessageType,
      fileSchema: MessageType,
      isCaseSensitive: Boolean): MessageType = {
    val fs = fileSchema.asGroupType()
    val types = if (isCaseSensitive) {
      val inFile = fs.getFields.asScala.map(_.getName).toSet
      clippedSchema.asGroupType()
        .getFields.asScala.filter(f => inFile.contains(f.getName))
    } else {
      val inFile = fs.getFields.asScala
        .map(_.getName.toLowerCase(Locale.ROOT)).toSet
      clippedSchema.asGroupType()
        .getFields.asScala
        .filter(f => inFile.contains(f.getName.toLowerCase(Locale.ROOT)))
    }
    if (types.isEmpty) {
      Types.buildMessage().named("spark_schema")
    } else {
      Types
        .buildMessage()
        .addFields(types: _*)
        .named("spark_schema")
    }
  }

  // Copied from Spark
  private val SPARK_VERSION_METADATA_KEY = "org.apache.spark.version"
  // Copied from Spark
  private val SPARK_LEGACY_DATETIME = "org.apache.spark.legacyDateTime"

  def isCorrectedRebaseMode(
      lookupFileMeta: String => String,
      isCorrectedModeConfig: Boolean): Boolean = {
    // If there is no version, we return the mode specified by the config.
    Option(lookupFileMeta(SPARK_VERSION_METADATA_KEY)).map { version =>
      // Files written by Spark 2.4 and earlier follow the legacy hybrid calendar and we need to
      // rebase the datetime values.
      // Files written by Spark 3.0 and later may also need the rebase if they were written with
      // the "LEGACY" rebase mode.
      version >= "3.0.0" && lookupFileMeta(SPARK_LEGACY_DATETIME) == null
    }.getOrElse(isCorrectedModeConfig)
  }
}

// contains meta about all the blocks in a file
private case class ParquetFileInfoWithBlockMeta(filePath: Path, blocks: Seq[BlockMetaData],
    partValues: InternalRow, schema: MessageType, isCorrectedRebaseMode: Boolean)

private case class GpuParquetFileFilterHandler(@transient sqlConf: SQLConf) extends Arm {
  private val isCaseSensitive = sqlConf.caseSensitiveAnalysis
  private val enableParquetFilterPushDown: Boolean = sqlConf.parquetFilterPushDown
  private val pushDownDate = sqlConf.parquetFilterPushDownDate
  private val pushDownTimestamp = sqlConf.parquetFilterPushDownTimestamp
  private val pushDownDecimal = sqlConf.parquetFilterPushDownDecimal
  private val pushDownStringStartWith = sqlConf.parquetFilterPushDownStringStartWith
  private val pushDownInFilterThreshold = sqlConf.parquetFilterPushDownInFilterThreshold
  private val isCorrectedRebase =
    "CORRECTED" == sqlConf.getConf(SQLConf.LEGACY_PARQUET_REBASE_MODE_IN_READ)

  def filterBlocks(
      file: PartitionedFile,
      conf : Configuration,
      filters: Array[Filter],
      readDataSchema: StructType): ParquetFileInfoWithBlockMeta = {

    val filePath = new Path(new URI(file.filePath))
    //noinspection ScalaDeprecation
    val footer = ParquetFileReader.readFooter(conf, filePath,
      ParquetMetadataConverter.range(file.start, file.start + file.length))
    val fileSchema = footer.getFileMetaData.getSchema
    val pushedFilters = if (enableParquetFilterPushDown) {
      val parquetFilters = new ParquetFilters(fileSchema, pushDownDate, pushDownTimestamp,
        pushDownDecimal, pushDownStringStartWith, pushDownInFilterThreshold, isCaseSensitive)
      filters.flatMap(parquetFilters.createFilter).reduceOption(FilterApi.and)
    } else {
      None
    }

    val isCorrectedRebaseForThisFile =
    GpuParquetPartitionReaderFactoryBase.isCorrectedRebaseMode(
      footer.getFileMetaData.getKeyValueMetaData.get, isCorrectedRebase)

    val blocks = if (pushedFilters.isDefined) {
      // Use the ParquetFileReader to perform dictionary-level filtering
      ParquetInputFormat.setFilterPredicate(conf, pushedFilters.get)
      //noinspection ScalaDeprecation
      withResource(new ParquetFileReader(conf, footer.getFileMetaData, filePath,
        footer.getBlocks, Collections.emptyList[ColumnDescriptor])) { parquetReader =>
        parquetReader.getRowGroups
      }
    } else {
      footer.getBlocks
    }

    val clippedSchemaTmp = ParquetReadSupport.clipParquetSchema(fileSchema, readDataSchema,
      isCaseSensitive)
    // ParquetReadSupport.clipParquetSchema does most of what we want, but it includes
    // everything in readDataSchema, even if it is not in fileSchema we want to remove those
    // for our own purposes
    val clippedSchema = GpuParquetPartitionReaderFactoryBase.filterClippedSchema(clippedSchemaTmp,
      fileSchema, isCaseSensitive)
    val columnPaths = clippedSchema.getPaths.asScala.map(x => ColumnPath.get(x: _*))
    val clipped = ParquetPartitionReader.clipBlocks(columnPaths, blocks.asScala)
    ParquetFileInfoWithBlockMeta(filePath, clipped, file.partitionValues,
      clippedSchema, isCorrectedRebaseForThisFile)
  }
}

/**
 * Similar to GpuParquetPartitionReaderFactory but extended for reading multiple files
 * in an iteration. This will allow us to read multiple small files and combine them
 * on the CPU side before sending them down to the GPU.
 */
case class GpuParquetMultiFilePartitionReaderFactory(
    @transient sqlConf: SQLConf,
    broadcastedConf: Broadcast[SerializableConfiguration],
    dataSchema: StructType,
    readDataSchema: StructType,
    partitionSchema: StructType,
    filters: Array[Filter],
    @transient rapidsConf: RapidsConf,
    metrics: Map[String, SQLMetric]) extends PartitionReaderFactory with Arm with Logging {
  private val isCaseSensitive = sqlConf.caseSensitiveAnalysis
  private val debugDumpPrefix = rapidsConf.parquetDebugDumpPrefix
  private val maxReadBatchSizeRows = rapidsConf.maxReadBatchSizeRows
  private val maxReadBatchSizeBytes = rapidsConf.maxReadBatchSizeBytes
  private val numThreads = rapidsConf.parquetMultiThreadReadNumThreads
  private val maxNumFileProcessed = rapidsConf.maxNumParquetFilesParallel

  private val filterHandler = new GpuParquetFileFilterHandler(sqlConf)

  override def supportColumnarReads(partition: InputPartition): Boolean = true

  override def createReader(partition: InputPartition): PartitionReader[InternalRow] = {
    throw new IllegalStateException("GPU column parser called to read rows")
  }

  override def createColumnarReader(partition: InputPartition): PartitionReader[ColumnarBatch] = {
    assert(partition.isInstanceOf[FilePartition])
    val filePartition = partition.asInstanceOf[FilePartition]
    val files = filePartition.files
    buildBaseColumnarParquetReader(files)
  }

  private def buildBaseColumnarParquetReader(
      files: Array[PartitionedFile]): PartitionReader[ColumnarBatch] = {
    val conf = broadcastedConf.value.value
    logDebug(s"Number files being read: ${files.size} for task ${TaskContext.get().partitionId()}")
    new MultiFileParquetPartitionReader(conf, files,
      isCaseSensitive, readDataSchema, debugDumpPrefix,
      maxReadBatchSizeRows, maxReadBatchSizeBytes, metrics, partitionSchema,
      numThreads, maxNumFileProcessed, filterHandler, filters)
  }
}

case class GpuParquetPartitionReaderFactory(
    @transient sqlConf: SQLConf,
    broadcastedConf: Broadcast[SerializableConfiguration],
    dataSchema: StructType,
    readDataSchema: StructType,
    partitionSchema: StructType,
    filters: Array[Filter],
    @transient rapidsConf: RapidsConf,
    metrics: Map[String, SQLMetric]) extends FilePartitionReaderFactory with Arm with Logging {
  private val isCaseSensitive = sqlConf.caseSensitiveAnalysis
  private val debugDumpPrefix = rapidsConf.parquetDebugDumpPrefix
  private val maxReadBatchSizeRows = rapidsConf.maxReadBatchSizeRows
  private val maxReadBatchSizeBytes = rapidsConf.maxReadBatchSizeBytes

  private val filterHandler = new GpuParquetFileFilterHandler(sqlConf)

  override def supportColumnarReads(partition: InputPartition): Boolean = true

  override def buildReader(partitionedFile: PartitionedFile): PartitionReader[InternalRow] = {
    throw new IllegalStateException("GPU column parser called to read rows")
  }

  override def buildColumnarReader(
      partitionedFile: PartitionedFile): PartitionReader[ColumnarBatch] = {
    val reader = buildBaseColumnarParquetReader(partitionedFile)
    ColumnarPartitionReaderWithPartitionValues.newReader(partitionedFile, reader, partitionSchema)
  }

  private def buildBaseColumnarParquetReader(
      file: PartitionedFile): PartitionReader[ColumnarBatch] = {
    val conf = broadcastedConf.value.value
    val singleFileInfo = filterHandler.filterBlocks(file, conf, filters, readDataSchema)
    new ParquetPartitionReader(conf, file, singleFileInfo.filePath, singleFileInfo.blocks,
      singleFileInfo.schema, isCaseSensitive, readDataSchema,
      debugDumpPrefix, maxReadBatchSizeRows,
      maxReadBatchSizeBytes, metrics, singleFileInfo.isCorrectedRebaseMode)
  }
}

/**
 * Base classes with common functions for MultiFileParquetPartitionReader and ParquetPartitionReader
 */
abstract class FileParquetPartitionReaderBase(
    conf: Configuration,
    isSchemaCaseSensitive: Boolean,
    readDataSchema: StructType,
    debugDumpPrefix: String,
    execMetrics: Map[String, SQLMetric]) extends PartitionReader[ColumnarBatch] with Logging
  with ScanWithMetrics with Arm {

  protected var isDone: Boolean = false
  protected var maxDeviceMemory: Long = 0
  protected var batch: Option[ColumnarBatch] = None
  protected val copyBufferSize = conf.getInt("parquet.read.allocation.size", 8 * 1024 * 1024)
  metrics = execMetrics

  override def get(): ColumnarBatch = {
    val ret = batch.getOrElse(throw new NoSuchElementException)
    batch = None
    ret
  }

  override def close(): Unit = {
    batch.foreach(_.close())
    batch = None
    isDone = true
  }

  protected def calculateParquetFooterSize(
      currentChunkedBlocks: Seq[BlockMetaData],
      schema: MessageType): Long = {
    // Calculate size of the footer metadata.
    // This uses the column metadata from the original file, but that should
    // always be at least as big as the updated metadata in the output.
    val out = new CountingOutputStream(new NullOutputStream)
    writeFooter(out, currentChunkedBlocks, schema)
    out.getByteCount
  }

  protected def calculateParquetOutputSize(
      currentChunkedBlocks: Seq[BlockMetaData],
      schema: MessageType): Long = {
    // start with the size of Parquet magic (at start+end) and footer length values
    var size: Long = 4 + 4 + 4

    // Calculate the total amount of column data that will be copied
    // NOTE: Avoid using block.getTotalByteSize here as that is the
    //       uncompressed size rather than the size in the file.
    size += currentChunkedBlocks.flatMap(_.getColumns.asScala.map(_.getTotalSize)).sum

    val footerSize = calculateParquetFooterSize(currentChunkedBlocks, schema)
    val totalSize = size + footerSize
    totalSize
  }

  protected def writeFooter(
      out: OutputStream,
      blocks: Seq[BlockMetaData],
      schema: MessageType): Unit = {
    val fileMeta = new FileMetaData(schema, Collections.emptyMap[String, String],
      ParquetPartitionReader.PARQUET_CREATOR)
    val metadataConverter = new ParquetMetadataConverter
    val footer = new ParquetMetadata(fileMeta, blocks.asJava)
    val meta = metadataConverter.toParquetMetadata(ParquetPartitionReader.PARQUET_VERSION, footer)
    org.apache.parquet.format.Util.writeFileMetaData(meta, out)
  }

  protected def copyDataRange(
      range: CopyRange,
      in: FSDataInputStream,
      out: OutputStream,
      copyBuffer: Array[Byte]): Unit = {
    if (in.getPos != range.offset) {
      in.seek(range.offset)
    }
    var bytesLeft = range.length
    while (bytesLeft > 0) {
      // downcast is safe because copyBuffer.length is an int
      val readLength = Math.min(bytesLeft, copyBuffer.length).toInt
      in.readFully(copyBuffer, 0, readLength)
      out.write(copyBuffer, 0, readLength)
      bytesLeft -= readLength
    }
  }

  /**
   * Copies the data corresponding to the clipped blocks in the original file and compute the
   * block metadata for the output. The output blocks will contain the same column chunk
   * metadata but with the file offsets updated to reflect the new position of the column data
   * as written to the output.
   *
   * @param in  the input stream for the original Parquet file
   * @param out the output stream to receive the data
   * @return updated block metadata corresponding to the output
   */
  protected def copyBlocksData(
      in: FSDataInputStream,
      out: HostMemoryOutputStream,
      blocks: Seq[BlockMetaData]): Seq[BlockMetaData] = {
    var totalRows: Long = 0
    val outputBlocks = new ArrayBuffer[BlockMetaData](blocks.length)
    val copyRanges = new ArrayBuffer[CopyRange]
    var currentCopyStart = 0L
    var currentCopyEnd = 0L
    var totalBytesToCopy = 0L
    blocks.foreach { block =>
      totalRows += block.getRowCount
      val columns = block.getColumns.asScala
      val outputColumns = new ArrayBuffer[ColumnChunkMetaData](columns.length)
      columns.foreach { column =>
        // update column metadata to reflect new position in the output file
        val offsetAdjustment = out.getPos + totalBytesToCopy - column.getStartingPos
        val newDictOffset = if (column.getDictionaryPageOffset > 0) {
          column.getDictionaryPageOffset + offsetAdjustment
        } else {
          0
        }
        //noinspection ScalaDeprecation
        outputColumns += ColumnChunkMetaData.get(
          column.getPath,
          column.getPrimitiveType,
          column.getCodec,
          column.getEncodingStats,
          column.getEncodings,
          column.getStatistics,
          column.getStartingPos + offsetAdjustment,
          newDictOffset,
          column.getValueCount,
          column.getTotalSize,
          column.getTotalUncompressedSize)

        if (currentCopyEnd != column.getStartingPos) {
          if (currentCopyEnd != 0) {
            copyRanges.append(CopyRange(currentCopyStart, currentCopyEnd - currentCopyStart))
          }
          currentCopyStart = column.getStartingPos
          currentCopyEnd = currentCopyStart
        }
        currentCopyEnd += column.getTotalSize
        totalBytesToCopy += column.getTotalSize
      }
      outputBlocks += ParquetPartitionReader.newParquetBlock(block.getRowCount, outputColumns)
    }

    if (currentCopyEnd != currentCopyStart) {
      copyRanges.append(CopyRange(currentCopyStart, currentCopyEnd - currentCopyStart))
    }
    val copyBuffer = new Array[Byte](copyBufferSize)
    copyRanges.foreach(copyRange => copyDataRange(copyRange, in, out, copyBuffer))
    outputBlocks
  }

  protected def areNamesEquiv(groups: GroupType, index: Int, otherName: String,
      isCaseSensitive: Boolean): Boolean = {
    if (groups.getFieldCount > index) {
      if (isCaseSensitive) {
        groups.getFieldName(index) == otherName
      } else {
        groups.getFieldName(index).toLowerCase(Locale.ROOT) == otherName.toLowerCase(Locale.ROOT)
      }
    } else {
      false
    }
  }

  protected def evolveSchemaIfNeededAndClose(
      inputTable: Table,
      filePath: String,
      clippedSchema: MessageType): Table = {
    if (readDataSchema.length > inputTable.getNumberOfColumns) {
      // Spark+Parquet schema evolution is relatively simple with only adding/removing columns
      // To type casting or anyting like that
      val clippedGroups = clippedSchema.asGroupType()
      val newColumns = new Array[ColumnVector](readDataSchema.length)
      try {
        withResource(inputTable) { table =>
          var readAt = 0
          (0 until readDataSchema.length).foreach(writeAt => {
            val readField = readDataSchema(writeAt)
            if (areNamesEquiv(clippedGroups, readAt, readField.name, isSchemaCaseSensitive)) {
              newColumns(writeAt) = table.getColumn(readAt).incRefCount()
              readAt += 1
            } else {
              withResource(GpuScalar.from(null, readField.dataType)) { n =>
                newColumns(writeAt) = ColumnVector.fromScalar(n, table.getRowCount.toInt)
              }
            }
          })
          if (readAt != table.getNumberOfColumns) {
            throw new QueryExecutionException(s"Could not find the expected columns " +
              s"$readAt out of ${table.getNumberOfColumns} from $filePath")
          }
        }
        new Table(newColumns: _*)
      } finally {
        newColumns.safeClose()
      }
    } else {
      inputTable
    }
  }

  protected def dumpParquetData(
      hmb: HostMemoryBuffer,
      dataLength: Long,
      splits: Array[PartitionedFile]): Unit = {
    val (out, path) = FileUtils.createTempFile(conf, debugDumpPrefix, ".parquet")
    try {
      logInfo(s"Writing Parquet split data for $splits to $path")
      val in = new HostMemoryInputStream(hmb, dataLength)
      IOUtils.copy(in, out)
    } finally {
      out.close()
    }
  }

  protected def readPartFile(
      blocks: Seq[BlockMetaData],
      clippedSchema: MessageType,
      filePath: Path): (HostMemoryBuffer, Long) = {
    withResource(new NvtxWithMetrics("Buffer file split", NvtxColor.YELLOW,
      metrics("bufferTime"))) { _ =>
      withResource(filePath.getFileSystem(conf).open(filePath)) { in =>
        val estTotalSize = calculateParquetOutputSize(blocks, clippedSchema)
        closeOnExcept(HostMemoryBuffer.allocate(estTotalSize)) { hmb =>
          val out = new HostMemoryOutputStream(hmb)
          out.write(ParquetPartitionReader.PARQUET_MAGIC)
          val outputBlocks = copyBlocksData(in, out, blocks)
          val footerPos = out.getPos
          writeFooter(out, outputBlocks, clippedSchema)

          BytesUtils.writeIntLittleEndian(out, (out.getPos - footerPos).toInt)
          out.write(ParquetPartitionReader.PARQUET_MAGIC)
          // check we didn't go over memory
          if (out.getPos > estTotalSize) {
            throw new QueryExecutionException(s"Calculated buffer size $estTotalSize is to " +
              s"small, actual written: ${out.getPos}")
          }
          (hmb, out.getPos)
        }
      }
    }
  }

  protected def populateCurrentBlockChunk(
      blockIter: BufferedIterator[BlockMetaData],
      maxReadBatchSizeRows: Int,
      maxReadBatchSizeBytes: Long): Seq[BlockMetaData] = {
    val currentChunk = new ArrayBuffer[BlockMetaData]
    var numRows: Long = 0
    var numBytes: Long = 0
    var numParquetBytes: Long = 0

    @tailrec
    def readNextBatch(): Unit = {
      if (blockIter.hasNext) {
        val peekedRowGroup = blockIter.head
        if (peekedRowGroup.getRowCount > Integer.MAX_VALUE) {
          throw new UnsupportedOperationException("Too many rows in split")
        }
        if (numRows == 0 || numRows + peekedRowGroup.getRowCount <= maxReadBatchSizeRows) {
          val estimatedBytes = GpuBatchUtils.estimateGpuMemory(readDataSchema,
            peekedRowGroup.getRowCount)
          if (numBytes == 0 || numBytes + estimatedBytes <= maxReadBatchSizeBytes) {
            currentChunk += blockIter.next()
            numRows += currentChunk.last.getRowCount
            numParquetBytes += currentChunk.last.getTotalByteSize
            numBytes += estimatedBytes
            readNextBatch()
          }
        }
      }
    }
    readNextBatch()
    logDebug(s"Loaded $numRows rows from Parquet. Parquet bytes read: $numParquetBytes. " +
      s"Estimated GPU bytes: $numBytes")
    currentChunk
  }

}

// Singleton threadpool that is used across all the tasks.
// Please note that the TaskContext is not set in these threads and should not be used.
object MultiFileThreadPoolFactory {

  private var threadPool: Option[ThreadPoolExecutor] = None

  private def initThreadPool(
      maxThreads: Int = 20,
      keepAliveSeconds: Long = 60): ThreadPoolExecutor = synchronized {
    if (!threadPool.isDefined) {
      val threadFactory = new ThreadFactoryBuilder()
        .setNameFormat("parquet reader worker-%d")
        .setDaemon(true)
        .build()

      threadPool = Some(new ThreadPoolExecutor(
        maxThreads, // corePoolSize: max number of threads to create before queuing the tasks
        maxThreads, // maximumPoolSize: because we use LinkedBlockingDeque, this is not used
        keepAliveSeconds,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue[Runnable],
        threadFactory))
      threadPool.get.allowCoreThreadTimeOut(true)
    }
    threadPool.get
  }

  def submitToThreadPool[T](task: Callable[T], numThreads: Int): Future[T] = {
    val pool = threadPool.getOrElse(initThreadPool(numThreads))
    pool.submit(task)
  }
}

/**
 * A PartitionReader that can read multiple Parquet files in parallel.
 *
 * Efficiently reading a Parquet split on the GPU requires re-constructing the Parquet file
 * in memory that contains just the column chunks that are needed. This avoids sending
 * unnecessary data to the GPU and saves GPU memory.
 *
 * @param conf the Hadoop configuration
 * @param files the partitioned files to read
 * @param isSchemaCaseSensitive whether schema is case sensitive
 * @param readDataSchema the Spark schema describing what will be read
 * @param debugDumpPrefix a path prefix to use for dumping the fabricated Parquet data or null
 * @param maxReadBatchSizeRows soft limit on the maximum number of rows the reader reads per batch
 * @param maxReadBatchSizeBytes soft limit on the maximum number of bytes the reader reads per batch
 * @param execMetrics metrics
 * @param partitionSchema Schema of partitions.
 * @param numThreads the size of the threadpool
 * @param maxNumFileProcessed the maximum number of files to read on the CPU side and waiting to be
 *                            processed on the GPU. This affects the amount of host memory used.
 * @param filterHandler GpuParquetFileFilterHandler used to filter the parquet blocks
 * @param filters filters passed into the filterHandler
 */
class MultiFileParquetPartitionReader(
    conf: Configuration,
    files: Array[PartitionedFile],
    isSchemaCaseSensitive: Boolean,
    readDataSchema: StructType,
    debugDumpPrefix: String,
    maxReadBatchSizeRows: Integer,
    maxReadBatchSizeBytes: Long,
    execMetrics: Map[String, SQLMetric],
    partitionSchema: StructType,
    numThreads: Int,
    maxNumFileProcessed: Int,
    filterHandler: GpuParquetFileFilterHandler,
    filters: Array[Filter])
  extends FileParquetPartitionReaderBase(conf, isSchemaCaseSensitive, readDataSchema,
    debugDumpPrefix, execMetrics) {

  case class HostMemoryBuffersWithMetaData(isCorrectRebaseMode: Boolean, clippedSchema: MessageType,
      partValues: InternalRow, memBuffersAndSizes: Array[(HostMemoryBuffer, Long)],
      fileName: String, fileStart: Long, fileLength: Long)

  private var filesToRead = 0
  private var currentFileHostBuffers: Option[HostMemoryBuffersWithMetaData] = None
  private var isInitted = false
  private val tasks = new ConcurrentLinkedQueue[Future[HostMemoryBuffersWithMetaData]]()
  private val tasksToRun = new Queue[ReadBatchRunner]()

  private class ReadBatchRunner(filterHandler: GpuParquetFileFilterHandler,
      file: PartitionedFile,
      conf: Configuration,
      filters: Array[Filter]) extends Callable[HostMemoryBuffersWithMetaData] with Logging {

    private var blockChunkIter: BufferedIterator[BlockMetaData] = null

    /**
     * Returns the host memory buffers and file meta data for the file processed.
     * If there was an error then the error field is set. If there were no blocks the buffer
     * is returned as null.  If there were no columns but rows (count() operation) then the
     * buffer is null and the size is the number of rows.
     *
     * Note that the TaskContext is not set in these threads and should not be used.
     */
    override def call(): HostMemoryBuffersWithMetaData = {
      val hostBuffers = new ArrayBuffer[(HostMemoryBuffer, Long)]
      try {
        val fileBlockMeta = filterHandler.filterBlocks(file, conf, filters, readDataSchema)
        if (fileBlockMeta.blocks.length == 0) {
          // no blocks so return null buffer and size 0
          return HostMemoryBuffersWithMetaData(fileBlockMeta.isCorrectedRebaseMode,
            fileBlockMeta.schema, fileBlockMeta.partValues, Array((null, 0)),
            file.filePath, file.start, file.length)
        }
        blockChunkIter = fileBlockMeta.blocks.iterator.buffered
        if (isDone) {
          // got close before finishing
          HostMemoryBuffersWithMetaData(
            fileBlockMeta.isCorrectedRebaseMode,
            fileBlockMeta.schema, fileBlockMeta.partValues, Array((null, 0)),
            file.filePath, file.start, file.length)
        } else {
          if (readDataSchema.isEmpty) {
            val numRows = fileBlockMeta.blocks.map(_.getRowCount).sum.toInt
            // overload size to be number of rows with null buffer
            HostMemoryBuffersWithMetaData(fileBlockMeta.isCorrectedRebaseMode,
              fileBlockMeta.schema, fileBlockMeta.partValues, Array((null, numRows)),
              file.filePath, file.start, file.length)

          } else {
            val filePath = new Path(new URI(file.filePath))
            while (blockChunkIter.hasNext) {
              val blocksToRead = populateCurrentBlockChunk(blockChunkIter,
                maxReadBatchSizeRows, maxReadBatchSizeBytes)
              val blockTotalSize = blocksToRead.map(_.getTotalByteSize).sum
              hostBuffers += readPartFile(blocksToRead, fileBlockMeta.schema, filePath)
            }
            if (isDone) {
              // got close before finishing
              hostBuffers.foreach(_._1.safeClose())
              HostMemoryBuffersWithMetaData(fileBlockMeta.isCorrectedRebaseMode,
                fileBlockMeta.schema, fileBlockMeta.partValues, Array((null, 0)),
                file.filePath, file.start, file.length)
            } else {
              HostMemoryBuffersWithMetaData(fileBlockMeta.isCorrectedRebaseMode,
                fileBlockMeta.schema, fileBlockMeta.partValues, hostBuffers.toArray,
                file.filePath, file.start, file.length)
            }
          }
        }
      } catch {
        case e: Throwable =>
          hostBuffers.foreach(_._1.safeClose())
          throw e
      }
    }
  }

  private def initAndStartReaders(): Unit = {
    // limit the number we submit at once according to the config if set
    val limit = math.min(maxNumFileProcessed, files.length)
    for (i <- 0 until limit) {
      val file = files(i)
      // Add these in the order as we got them so that we can make sure
      // we process them in the same order as CPU would.
      tasks.add(MultiFileThreadPoolFactory.submitToThreadPool(
        new ReadBatchRunner(filterHandler, file, conf, filters), numThreads))
    }
    // queue up any left to add once others finish
    for (i <- limit until files.length) {
      val file = files(i)
      tasksToRun.enqueue(new ReadBatchRunner(filterHandler, file, conf, filters))
    }
    isInitted = true
    filesToRead = files.length
  }

  private def readBatch(
      fileBufsAndMeta: HostMemoryBuffersWithMetaData): Option[ColumnarBatch] = {
    val memBuffersAndSize = fileBufsAndMeta.memBuffersAndSizes
    val (hostbuffer, size) = memBuffersAndSize.head
    val nextBatch = readBufferToTable(fileBufsAndMeta.isCorrectRebaseMode,
        fileBufsAndMeta.clippedSchema, fileBufsAndMeta.partValues,
        hostbuffer, size, fileBufsAndMeta.fileName)

    if (memBuffersAndSize.length > 1) {
      val updatedBuffers = memBuffersAndSize.drop(1)
      currentFileHostBuffers = Some(fileBufsAndMeta.copy(memBuffersAndSizes = updatedBuffers))
    } else {
      currentFileHostBuffers = None
    }
    nextBatch
  }

  private def getSizeOfHostBuffers(fileInfo: HostMemoryBuffersWithMetaData): Long = {
    fileInfo.memBuffersAndSizes.map(_._2).sum
  }

  private def addNextTaskIfNeeded(): Unit = {
    if (tasksToRun.size > 0 && !isDone) {
      val runner = tasksToRun.dequeue()
      tasks.add(MultiFileThreadPoolFactory.submitToThreadPool(runner, numThreads))
    }
  }

  override def next(): Boolean = {
    withResource(new NvtxWithMetrics("Parquet readBatch", NvtxColor.GREEN,
      metrics(TOTAL_TIME))) { _ =>
      if (isInitted == false) {
        initAndStartReaders()
      }
      batch.foreach(_.close())
      batch = None
      // if we have batch left from the last file read return it
      if (currentFileHostBuffers.isDefined) {
        if (getSizeOfHostBuffers(currentFileHostBuffers.get) == 0) {
          next()
        }
        batch = readBatch(currentFileHostBuffers.get)
      } else {
        currentFileHostBuffers = None
        if (filesToRead > 0 && !isDone) {
          val fileBufsAndMeta = tasks.poll.get()
          filesToRead -= 1
          InputFileUtils.setInputFileBlock(fileBufsAndMeta.fileName, fileBufsAndMeta.fileStart,
            fileBufsAndMeta.fileLength)

          if (getSizeOfHostBuffers(fileBufsAndMeta) == 0) {
            // if sizes are 0 means no rows and no data so skip to next file
            // file data was empty so submit another task if any were waiting
            addNextTaskIfNeeded()
            next()
          } else {
            batch = readBatch(fileBufsAndMeta)
            // the data is copied to GPU so submit another task if we were limited
            addNextTaskIfNeeded()
          }
        } else {
          isDone = true
          metrics("peakDevMemory") += maxDeviceMemory
        }
      }
    }

    // this shouldn't happen but if somehow the batch is None and we still
    // have work left skip to the next file
    if (!batch.isDefined && filesToRead > 0 && !isDone) {
      next()
    }

    // This is odd, but some operators return data even when there is no input so we need to
    // be sure that we grab the GPU
    GpuSemaphore.acquireIfNecessary(TaskContext.get())
    batch.isDefined
  }

  override def close(): Unit = {
    // this is more complicated because threads might still be processing files
    // in cases close got called early for like limit() calls
    isDone = true
    currentFileHostBuffers.foreach { current =>
      current.memBuffersAndSizes.foreach { case (buf, size) =>
        if (buf != null) {
          buf.close()
        }
      }
    }
    currentFileHostBuffers = None
    batch.foreach(_.close())
    batch = None
    tasks.asScala.foreach { task =>
      if (task.isDone()) {
        task.get.memBuffersAndSizes.foreach { case (buf, size) =>
          if (buf != null) {
            buf.close()
          }
        }
      } else {
        // Note we are not interrupting thread here so it
        // will finish reading and then just discard. If we
        // interrupt HDFS logs warnings about being interrupted.
        task.cancel(false)
      }
    }
  }

  private def addPartitionValues(
      batch: Option[ColumnarBatch],
      inPartitionValues: InternalRow): Option[ColumnarBatch] = {
    batch.map { cb =>
      val partitionValues = inPartitionValues.toSeq(partitionSchema)
      val partitionScalars = ColumnarPartitionReaderWithPartitionValues
        .createPartitionValues(partitionValues, partitionSchema)
      withResource(partitionScalars) { scalars =>
        ColumnarPartitionReaderWithPartitionValues.addPartitionValues(cb, scalars)
      }
    }
  }

  private def readBufferToTable(
      isCorrectRebaseMode: Boolean,
      clippedSchema: MessageType,
      partValues: InternalRow,
      hostBuffer: HostMemoryBuffer,
      dataSize: Long,
      fileName: String): Option[ColumnarBatch] = {
    if (dataSize == 0) {
      // shouldn't ever get here
      None
    }
    // not reading any data, so return a degenerate ColumnarBatch with the row count
    if (hostBuffer == null) {
      return Some(new ColumnarBatch(Array.empty, dataSize.toInt))
    }
    val table = withResource(hostBuffer) { _ =>
      if (debugDumpPrefix != null) {
        dumpParquetData(hostBuffer, dataSize, files)
      }
      val parseOpts = ParquetOptions.builder()
        .withTimeUnit(DType.TIMESTAMP_MICROSECONDS)
        .includeColumn(readDataSchema.fieldNames: _*).build()

      // about to start using the GPU
      GpuSemaphore.acquireIfNecessary(TaskContext.get())

      val table = withResource(new NvtxWithMetrics("Parquet decode", NvtxColor.DARK_GREEN,
        metrics(GPU_DECODE_TIME))) { _ =>
        Table.readParquet(parseOpts, hostBuffer, 0, dataSize)
      }
      closeOnExcept(table) { _ =>
        if (!isCorrectRebaseMode) {
          (0 until table.getNumberOfColumns).foreach { i =>
            if (RebaseHelper.isDateTimeRebaseNeededRead(table.getColumn(i))) {
              throw RebaseHelper.newRebaseExceptionInRead("Parquet")
            }
          }
        }
        maxDeviceMemory = max(GpuColumnVector.getTotalDeviceMemoryUsed(table), maxDeviceMemory)
        if (readDataSchema.length < table.getNumberOfColumns) {
          throw new QueryExecutionException(s"Expected ${readDataSchema.length} columns " +
            s"but read ${table.getNumberOfColumns} from $fileName")
        }
      }
      metrics(NUM_OUTPUT_BATCHES) += 1
      Some(evolveSchemaIfNeededAndClose(table, fileName, clippedSchema))
    }
    try {
      val maybeBatch = table.map(GpuColumnVector.from)
      maybeBatch.foreach { batch =>
        logDebug(s"GPU batch size: ${GpuColumnVector.getTotalDeviceMemoryUsed(batch)} bytes")
      }
      // we have to add partition values here for this batch, we already verified that
      // its not different for all the blocks in this batch
      addPartitionValues(maybeBatch, partValues)
    } finally {
      table.foreach(_.close())
    }
  }
}

/**
 * A PartitionReader that reads a Parquet file split on the GPU.
 *
 * Efficiently reading a Parquet split on the GPU requires re-constructing the Parquet file
 * in memory that contains just the column chunks that are needed. This avoids sending
 * unnecessary data to the GPU and saves GPU memory.
 *
 * @param conf the Hadoop configuration
 * @param split the file split to read
 * @param filePath the path to the Parquet file
 * @param clippedBlocks the block metadata from the original Parquet file that has been clipped
 *                      to only contain the column chunks to be read
 * @param clippedParquetSchema the Parquet schema from the original Parquet file that has been
 *                             clipped to contain only the columns to be read
 * @param readDataSchema the Spark schema describing what will be read
 * @param debugDumpPrefix a path prefix to use for dumping the fabricated Parquet data or null
 */
class ParquetPartitionReader(
    conf: Configuration,
    split: PartitionedFile,
    filePath: Path,
    clippedBlocks: Seq[BlockMetaData],
    clippedParquetSchema: MessageType,
    isSchemaCaseSensitive: Boolean,
    readDataSchema: StructType,
    debugDumpPrefix: String,
    maxReadBatchSizeRows: Integer,
    maxReadBatchSizeBytes: Long,
    execMetrics: Map[String, SQLMetric],
    isCorrectedRebaseMode: Boolean)  extends
  FileParquetPartitionReaderBase(conf,
    isSchemaCaseSensitive, readDataSchema, debugDumpPrefix, execMetrics) {

  private val blockIterator:  BufferedIterator[BlockMetaData] = clippedBlocks.iterator.buffered

  override def next(): Boolean = {
    batch.foreach(_.close())
    batch = None
    if (!isDone) {
      if (!blockIterator.hasNext) {
        isDone = true
        metrics("peakDevMemory") += maxDeviceMemory
      } else {
        batch = readBatch()
      }
    }
    // This is odd, but some operators return data even when there is no input so we need to
    // be sure that we grab the GPU
    GpuSemaphore.acquireIfNecessary(TaskContext.get())
    batch.isDefined
  }

  private def readBatch(): Option[ColumnarBatch] = {
    withResource(new NvtxWithMetrics("Parquet readBatch", NvtxColor.GREEN,
        metrics(TOTAL_TIME))) { _ =>
      val currentChunkedBlocks = populateCurrentBlockChunk(blockIterator,
        maxReadBatchSizeRows, maxReadBatchSizeBytes)
      if (readDataSchema.isEmpty) {
        // not reading any data, so return a degenerate ColumnarBatch with the row count
        val numRows = currentChunkedBlocks.map(_.getRowCount).sum.toInt
        if (numRows == 0) {
          None
        } else {
          Some(new ColumnarBatch(Array.empty, numRows.toInt))
        }
      } else {
        val table = readToTable(currentChunkedBlocks)
        try {
          val maybeBatch = table.map(GpuColumnVector.from)
          maybeBatch.foreach { batch =>
            logDebug(s"GPU batch size: ${GpuColumnVector.getTotalDeviceMemoryUsed(batch)} bytes")
          }
          maybeBatch
        } finally {
          table.foreach(_.close())
        }
      }
    }
  }

  private def readToTable(currentChunkedBlocks: Seq[BlockMetaData]): Option[Table] = {
    if (currentChunkedBlocks.isEmpty) {
      return None
    }
    val (dataBuffer, dataSize) = readPartFile(currentChunkedBlocks, clippedParquetSchema, filePath)
    try {
      if (dataSize == 0) {
        None
      } else {
        if (debugDumpPrefix != null) {
          dumpParquetData(dataBuffer, dataSize, Array(split))
        }
        val parseOpts = ParquetOptions.builder()
          .withTimeUnit(DType.TIMESTAMP_MICROSECONDS)
          .includeColumn(readDataSchema.fieldNames:_*).build()

        // about to start using the GPU
        GpuSemaphore.acquireIfNecessary(TaskContext.get())

        val table = withResource(new NvtxWithMetrics("Parquet decode", NvtxColor.DARK_GREEN,
            metrics(GPU_DECODE_TIME))) { _ =>
          Table.readParquet(parseOpts, dataBuffer, 0, dataSize)
        }
        closeOnExcept(table) { _ =>
          if (!isCorrectedRebaseMode) {
            (0 until table.getNumberOfColumns).foreach { i =>
              if (RebaseHelper.isDateTimeRebaseNeededRead(table.getColumn(i))) {
                throw RebaseHelper.newRebaseExceptionInRead("Parquet")
              }
            }
          }
          maxDeviceMemory = max(GpuColumnVector.getTotalDeviceMemoryUsed(table), maxDeviceMemory)
          if (readDataSchema.length < table.getNumberOfColumns) {
            throw new QueryExecutionException(s"Expected ${readDataSchema.length} columns " +
              s"but read ${table.getNumberOfColumns} from $filePath")
          }
        }
        metrics(NUM_OUTPUT_BATCHES) += 1
        Some(evolveSchemaIfNeededAndClose(table, filePath.toString, clippedParquetSchema))
      }
    } finally {
      dataBuffer.close()
    }
  }
}

object ParquetPartitionReader {
  private[rapids] val PARQUET_MAGIC = "PAR1".getBytes(StandardCharsets.US_ASCII)
  private[rapids] val PARQUET_CREATOR = "RAPIDS Spark Plugin"
  private[rapids] val PARQUET_VERSION = 1

  private[rapids] case class CopyRange(offset: Long, length: Long)

  /**
   * Build a new BlockMetaData
   *
   * @param rowCount the number of rows in this block
   * @param columns the new column chunks to reference in the new BlockMetaData
   * @return the new BlockMetaData
   */
  private[rapids] def newParquetBlock(
      rowCount: Long,
      columns: Seq[ColumnChunkMetaData]): BlockMetaData = {
    val block = new BlockMetaData
    block.setRowCount(rowCount)

    var totalSize: Long = 0
    columns.foreach { column =>
      block.addColumn(column)
      totalSize += column.getTotalUncompressedSize
    }
    block.setTotalByteSize(totalSize)

    block
  }

  /**
   * Trim block metadata to contain only the column chunks that occur in the specified columns.
   * The column chunks that are returned are preserved verbatim
   * (i.e.: file offsets remain unchanged).
   *
   * @param columnPaths the paths of columns to preserve
   * @param blocks the block metadata from the original Parquet file
   * @return the updated block metadata with undesired column chunks removed
   */
  private[spark] def clipBlocks(columnPaths: Seq[ColumnPath],
      blocks: Seq[BlockMetaData]): Seq[BlockMetaData] = {
    val pathSet = columnPaths.toSet
    blocks.map(oldBlock => {
      //noinspection ScalaDeprecation
      val newColumns = oldBlock.getColumns.asScala.filter(c => pathSet.contains(c.getPath))
      ParquetPartitionReader.newParquetBlock(oldBlock.getRowCount, newColumns)
    })
  }
}

