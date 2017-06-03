package amplab.alchemist
import org.apache.spark.mllib.linalg.distributed.{IndexedRow, IndexedRowMatrix}
import scala.math.max

class AlMatrix(val mal: Alchemist, mhandle: MatrixHandle) {
  val handle : MatrixHandle = mhandle
  val al : Alchemist = mal

  def getDimensions() : Tuple2[Long, Int] = {
    return al.client.getMatrixDimensions(handle)
  }

  // Caches result by default, because may not want to recreate (e.g. if delete referenced matrix on Alchemist side to save memory)
  def getIndexedRowMatrix() : IndexedRowMatrix = {
    val (numRows, numCols) = getDimensions()
    // TODO:
    // should map the rows back to the executors using locality information if possible
    // otherwise shuffle the rows on the MPI side before sending them back to SPARK
    val numPartitions = max(al.sc.defaultParallelism, al.client.workerCount)
    val sacrificialRDD = al.sc.parallelize(1 to numRows.toInt, numPartitions)
    val layout : Array[WorkerId] = (1 to sacrificialRDD.partitions.size).map(x => new WorkerId(x % al.client.workerCount)).toArray

    al.client.getIndexedRowMatrixStart(handle, layout)
    val rows = sacrificialRDD.mapPartitionsWithIndex( (idx, rowindices) => {
      val worker = al.context.connectWorker(layout(idx))
      val result  = rowindices.toList.map { rowIndex => 
        new IndexedRow(rowIndex, worker.getIndexedRowMatrix_getRow(handle, rowIndex))
      }.iterator
      worker.getIndexedRowMatrix_partitionComplete(handle)
      worker.close()
      result}, 
      preservesPartitioning=true)
    val result = new IndexedRowMatrix(rows, numRows, numCols)
    result.rows.cache()
    result.rows.count
    al.client.getIndexedRowMatrixFinish(handle)
    result
  } 

}

object AlMatrix {
  def apply(al: Alchemist, mat: IndexedRowMatrix): AlMatrix = {
    val ctx = al.context
    val workerIds = ctx.workerIds
    val layout = mat.rows.partitions.zipWithIndex.map {
      case (part, idx) => workerIds(idx % workerIds.length)
    }.toArray
    val handle = al.client.newMatrixStart(mat.numRows, mat.numCols, layout)
    mat.rows.mapPartitionsWithIndex { (idx, part) =>
      val client = ctx.connectWorker(layout(idx))
      part.foreach { row =>
        client.newMatrix_addRow(handle, row.index, row.vector.toArray)
      }
      client.newMatrix_partitionComplete(handle)
      client.close()
      Iterator.single(true)
    }.count
    al.client.newMatrixFinish(handle)
    new AlMatrix(al, handle)
  }
}