package au.com.dius.pact.model.unfiltered

import java.io.{BufferedReader, InputStreamReader}
import java.net.URI
import java.util
import java.util.zip.GZIPInputStream

import au.com.dius.pact.model.{OptionalBody, Request, Response}
import com.ning.http.client
import com.ning.http.client.FluentCaseInsensitiveStringsMap
import com.typesafe.scalalogging.StrictLogging
import io.netty.handler.codec.http.{HttpResponse => NHttpResponse}
import unfiltered.netty.ReceivedMessage
import unfiltered.request.HttpRequest
import unfiltered.response._

import scala.collection.JavaConversions
import scala.collection.immutable.Stream

object Conversions extends StrictLogging {

  def toMap(map: FluentCaseInsensitiveStringsMap): java.util.Map[String, String] = {
    import collection.JavaConversions._
    JavaConversions.mapAsJavaMap(map.entrySet().map(e => e.getKey -> e.getValue.mkString(",")).toMap)
  }

  implicit def dispatchResponseToPactResponse(response: client.Response): Response = {
    val contentType = if (response.getContentType == null)
        org.apache.http.entity.ContentType.APPLICATION_JSON
      else
        org.apache.http.entity.ContentType.parse(response.getContentType)
    val charset = if (contentType.getCharset == null) "UTF-8" else contentType.getCharset.name()
    val body = /*OptionalBody.body*/(response.getResponseBody(charset))
    //val r = new Response(response.getStatusCode, toMap(response.getHeaders), body)
    val responseMap = new util.HashMap[String, Any]()
    responseMap.put("status", response.getStatusCode)
    responseMap.put("headers", toMap(response.getHeaders))
    responseMap.put("body", body)
    val r = Response.fromMap(responseMap)
    logger.debug("response=" + r)
    r
  }

  case class Headers(headers: java.util.Map[String, String]) extends unfiltered.response.Responder[Any] {
    def respond(res: HttpResponse[Any]) {
      import collection.JavaConversions._
      if (headers != null) {
        headers.foreach { case (key, value) => res.header(key, value) }
      }
    }
  }

  implicit def pactToUnfilteredResponse(response: Response): ResponseFunction[NHttpResponse] = {
    if (response.getBody.isPresent) {
      Status(response.getStatus) ~> Headers(response.getHeaders) ~> ResponseString(response.getBody.getValue())
    } else Status(response.getStatus) ~> Headers(response.getHeaders)
  }

  def toHeaders(request: HttpRequest[ReceivedMessage]): java.util.Map[String, String] = {
    JavaConversions.mapAsJavaMap(request.headerNames.map(name =>
      name -> request.headers(name).mkString(",")).toMap)
  }

  def toQuery(request: HttpRequest[ReceivedMessage]): java.util.Map[String, java.util.List[String]] = {
    JavaConversions.mapAsJavaMap(request.parameterNames.map(name =>
      name -> JavaConversions.seqAsJavaList(request.parameterValues(name))).toMap)
  }

  def toPath(uri: String) = new URI(uri).getPath

  def toBody(request: HttpRequest[ReceivedMessage], charset: String = "UTF-8") = {
    val br = if (request.headers(ContentEncoding.GZip.name).contains("gzip")) {
      new BufferedReader(new InputStreamReader(new GZIPInputStream(request.inputStream)))
    } else {
      new BufferedReader(request.reader)
    }
    Stream.continually(br.readLine()).takeWhile(_ != null).mkString("\n")
  }

  implicit def unfilteredRequestToPactRequest(request: HttpRequest[ReceivedMessage]): Request = {
    val requestMap = new util.HashMap[String, Any]()
    requestMap.put("method", request.method)
    requestMap.put("path", toPath(request.uri))
    requestMap.put("query", toQuery(request))
    requestMap.put("headers", toHeaders(request))
    requestMap.put("body", toBody(request))
    Request.fromMap(requestMap)

    //new Request(request.method, toPath(request.uri), toQuery(request), toHeaders(request),
    //  OptionalBody.body(toBody(request)))
  }
}
