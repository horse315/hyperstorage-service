/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 */

package com.hypertino.hyperstorage.indexing

import com.hypertino.hyperstorage.db.{Content, Db, IndexContent, IndexDef}
import com.typesafe.scalalogging.StrictLogging
import monix.execution.Scheduler

import scala.concurrent.Future
import scala.util.control.NonFatal

trait ItemIndexer extends StrictLogging {
  def db: Db
  implicit def scheduler: Scheduler

  def indexItem(indexDef: IndexDef, item: Content, idFieldName: String, count: Option[Long]): Future[(String,Boolean)] = {
    val contentValue = item.bodyValue
    val sortBy = IndexLogic.extractSortFieldValues(idFieldName, indexDef.sortByParsed, contentValue)

    val write: Boolean = !item.isDeleted.contains(true) && (indexDef.filter.map { filter ⇒
      try {
        IndexLogic.evaluateFilterExpression(filter, contentValue)
      } catch {
        case NonFatal(e) ⇒
          logger.debug(s"Can't evaluate expression: `$filter` for $item", e)
          false
      }
    } getOrElse {
      true
    })

    logger.debug(s"Indexing item $item with $indexDef ... ${if (write) "Accepted" else "Rejected"}")

    if (write) {
      val indexContent = IndexContent(
        item.documentUri, indexDef.indexId, item.itemId, item.revision, count,
        if (indexDef.materialize) item.body else None,
        item.createdAt, item.modifiedAt
      )
      db.insertIndexItem(indexDef.tableName, sortBy, indexContent, item.realTtl) map { _ ⇒
        (item.itemId, write)
      }
    }
    else {
      Future.successful {
        (item.itemId, write)
      }
    }
  }
}
