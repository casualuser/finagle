package com.twitter.finagle.http.codec

import com.twitter.finagle.dispatch.GenSerialClientDispatcher
import com.twitter.finagle.http.{Fields, Request, Response}
import com.twitter.finagle.http.exp.{Multi, StreamTransport}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.util.{Future, Promise, Return}

/**
 * Client dispatcher for HTTP.
 *
 * @param statsReceiver typically scoped to `clientName/dispatcher`
 */
private[finagle] class HttpClientDispatcher(
  trans: StreamTransport[Request, Response],
  statsReceiver: StatsReceiver
) extends GenSerialClientDispatcher[Request, Response, Request, Multi[Response]](
      trans,
      statsReceiver
    ) {

  protected def dispatch(req: Request, p: Promise[Response]): Future[Unit] = {
    if (!req.isChunked && !req.headerMap.contains(Fields.ContentLength)) {
      val len = req.content.length
      // Only set the content length if we are sure there is content. This
      // behavior complies with the specification that user agents should not
      // set the content length header for messages without a payload body.
      if (len > 0) req.headerMap.set(Fields.ContentLength, len.toString)
    }

    // wait on these concurrently:
    Future
      .join(
        Seq(
          trans.write(req),
          // Drain the Transport into Response body.
          trans.read().flatMap {
            case Multi(res, readFinished) =>
              p.updateIfEmpty(Return(res))
              readFinished
          } // we don't need to satisfy p when we fail because GenSerialClientDispatcher does already
        )
      )
      .onFailure { _ =>
        // This Future represents the totality of the exchange;
        // thus failure represents *any* failure that can happen
        // during the exchange.
        req.reader.discard()
        trans.close()
      }
  }
}
