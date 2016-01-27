/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.databricks.dfgraph.lib

import org.apache.spark.graphx.{lib => graphxlib, Graph}
import org.apache.spark.sql.Row
import org.apache.spark.sql.types.{LongType, StructField, DoubleType, StructType}

import com.databricks.dfgraph.DFGraph

import scala.reflect.ClassTag
import scala.reflect.classTag
import scala.reflect.runtime.universe._

/**
 * PageRank algorithm implementation. There are two implementations of PageRank implemented.
 *
 * The first implementation uses the standalone [[DFGraph]] interface and runs PageRank
 * for a fixed number of iterations:
 * {{{
 * var PR = Array.fill(n)( 1.0 )
 * val oldPR = Array.fill(n)( 1.0 )
 * for( iter <- 0 until numIter ) {
 *   swap(oldPR, PR)
 *   for( i <- 0 until n ) {
 *     PR[i] = alpha + (1 - alpha) * inNbrs[i].map(j => oldPR[j] / outDeg[j]).sum
 *   }
 * }
 * }}}
 *
 * The second implementation uses the [[org.apache.spark.graphx.Pregel]] interface and runs PageRank until
 * convergence:
 *
 * {{{
 * var PR = Array.fill(n)( 1.0 )
 * val oldPR = Array.fill(n)( 0.0 )
 * while( max(abs(PR - oldPr)) > tol ) {
 *   swap(oldPR, PR)
 *   for( i <- 0 until n if abs(PR[i] - oldPR[i]) > tol ) {
 *     PR[i] = alpha + (1 - \alpha) * inNbrs[i].map(j => oldPR[j] / outDeg[j]).sum
 *   }
 * }
 * }}}
 *
 * `alpha` is the random reset probability (typically 0.15), `inNbrs[i]` is the set of
 * neighbors whick link to `i` and `outDeg[j]` is the out degree of vertex `j`.
 *
 * Note that this is not the "normalized" PageRank and as a consequence pages that have no
 * inlinks will have a PageRank of alpha.
 *
 * Result graphs:
 *
 * The result edges contain the following columns:
 *  - src: the id of the start vertex
 *  - dst: the id of the end vertex
 *  - weight: (double) the normalized weight of this edge after running PageRank
 * All the other columns are dropped.
 *
 * The result vertices contain the following columns:
 *  - id: the id of the vertex
 *  - weight (double): the normalized weight (page rank) of this vertex.
 * All the other columns are dropped.
 */
// TODO: srcID's type should be checked. The most futureproof check would be : Encoder, because it is compatible with
// Datasets after that.
object PageRank {
  /**
   * Run PageRank for a fixed number of iterations returning a graph
   * with vertex attributes containing the PageRank and edge
   * attributes the normalized edge weight.
   *
   * @param graph the graph on which to compute PageRank
   * @param numIter the number of iterations of PageRank to run
   * @param resetProb the random reset probability (alpha)
   *
   * @return the graph containing with each vertex containing the PageRank and each edge
   *         containing the normalized weight.
   */
  def run[VertexId : TypeTag](
      graph: DFGraph,
      numIter: Int,
      resetProb: Double = 0.15,
      srcId: Option[VertexId] = None): DFGraph = {
    // TODO(tjh) use encoder on srcId
    val gx = graphxlib.PageRank.runWithOptions(graph.cachedGraphX, numIter, resetProb, None)
    GraphXConversions.fromGraphX(graph, gx, vertexNames = Seq(WEIGHT), edgeName = Seq(WEIGHT))
  }

  /**
   * Run a dynamic version of PageRank returning a graph with vertex attributes containing the
   * PageRank and edge attributes containing the normalized edge weight.
   *
   * @param graph the graph on which to compute PageRank
   * @param tol the tolerance allowed at convergence (smaller => more accurate).
   * @param resetProb the random reset probability (alpha)
   * @param srcId the source vertex for a Personalized Page Rank (optional)
   *
   * @return the graph containing with each vertex containing the PageRank and each edge
   *         containing the normalized weight.
   */
  def runUntilConvergence[VertexId : TypeTag](
      graph: DFGraph,
      tol: Double,
      resetProb: Double = 0.15, srcId: Option[VertexId] = None): DFGraph = {
    // TODO(tjh) use the same type as the ID in the DFGRaph using a typetag.
    val gx = graphxlib.PageRank.runUntilConvergenceWithOptions(graph.cachedTopologyGraphX, tol, resetProb, None)
    GraphXConversions.fromGraphX(graph, gx, vertexNames = Seq(WEIGHT), edgeName = Seq(WEIGHT))
  }


  /**
   * Default name for the weight column.
   */
  private val WEIGHT = "weight"

  class Builder private[dfgraph] (
      graph: DFGraph) extends Arguments {

    private var tol: Option[Double] = None
    private var resetProb: Option[Double] = Some(0.15)
    private var numIters: Option[Int] = None
    private var srcId: Option[Long] = None

    def setSourceId[A : ClassTag](srcId_ : A): this.type = {
      val ct = implicitly[ClassTag[A]]
      require(ct == classTag[Long], "Only long are supported for now")
      srcId = Some(srcId_.asInstanceOf[Long])
      this
    }

    def setResetProbability(p: Double): this.type = {
      resetProb = Some(p)
      this
    }

    def untilConvergence(tolerance: Double): this.type = {
      tol = Some(tolerance)
      this
    }

    def fixedIterations(i: Int): this.type = {
      numIters = Some(i)
      this
    }

    def run(): DFGraph = {
      tol match {
        case Some(t) =>
          PageRank.runUntilConvergence(graph, t, resetProb.get, srcId)
        case None =>
          PageRank.run(graph, check(numIters, "numIters"), resetProb.get, srcId)
      }
    }
  }
}
