package au.com.dius.pact.provider.broker

import groovy.transform.Canonical
import groovy.util.logging.Slf4j
import groovyx.net.http.RESTClient

/**
 * HAL client for navigating the HAL links
 */
@Slf4j
@Canonical
@SuppressWarnings('DuplicateStringLiteral')
class HalClient {
  private static final String ROOT = '/'

  String baseUrl
  Map options = [:]
  def http
  private pathInfo

  @SuppressWarnings('DuplicateNumberLiteral')
  private void setupHttpClient() {
    if (http == null) {
      http = newHttpClient()
      if (options.authentication instanceof List) {
        switch (options.authentication.first().toLowerCase()) {
          case 'basic':
            if (options.authentication.size() > 2) {
              http.auth.basic(options.authentication[1].toString(), options.authentication[2].toString())
            } else {
              log.warn('Basic authentication requires a username and password, ignoring.')
            }
            break
        }
      } else if (options.authentication) {
        log.warn('Authentication options needs to be a list of values, ignoring.')
      }
    }
  }

  private newHttpClient() {
    http = new RESTClient(baseUrl)
    http.parser.'application/hal+json' = http.parser.'application/json'
    http
  }

  HalClient navigate(Map options = [:], String link) {
    pathInfo = pathInfo ?: fetch(ROOT)
    pathInfo = fetchLink(link, options)
    this
  }

  private fetchLink(String link, Map options) {
    if (pathInfo == null || pathInfo['_links'] == null) {
      throw new InvalidHalResponse('Expected a HAL+JSON response from the pact broker, but got ' +
        "a response with no '_links'. URL: '${baseUrl}', LINK: '${link}'")
    }

    def linkData = pathInfo.'_links'[link]
    if (linkData == null) {
      throw new InvalidHalResponse("Link '$link' was not found in the response, only the following links where " +
        "found: ${pathInfo['_links'].keySet()}. URL: '${baseUrl}', LINK: '${link}'")
    }

    if (linkData.templated) {
      fetch(parseLinkUrl(linkData.href, options))
    } else {
      fetch(linkData.href)
    }
  }

  static String parseLinkUrl(String linkUrl, Map options) {
    def m = linkUrl =~ /\{(\w+)\}/
    def result = ''
    int index = 0
    while (m.find()) {
      def start = m.start() - 1
      if (start >= index) {
        result += linkUrl[index..start]
      }
      index = m.end()
      def key = m.group(1)
      result += options[key] ?: m.group(0)
    }

    if (index < linkUrl.size()) {
      result += linkUrl[index..-1]
    }
    result
  }

  private fetch(String path) {
    setupHttpClient()
    log.debug "Fetching: $path"
    def response = http.get(path: path, requestContentType: 'application/json',
      headers: [Accept: 'application/hal+json'])
    def contentType = response.headers.'Content-Type'
    if (contentType != 'application/json' && contentType != 'application/hal+json') {
      throw new InvalidHalResponse('Expected a HAL+JSON response from the pact broker, but got ' +
        "'$contentType'. URL: '${baseUrl}', PATH: '${path}'")
    }
    response.data
  }

  def methodMissing(String name, args) {
    pathInfo = pathInfo ?: fetch(ROOT)
    def matchingLink = pathInfo.'_links'[name]
    if (matchingLink) {
      if (args && args.last() instanceof Closure) {
        if (matchingLink instanceof Collection) {
          return matchingLink.each(args.last() as Closure)
        }
        return args.last().call(matchingLink)
      }
      return matchingLink
    }
    throw new MissingMethodException(name, this.class, args)
  }
}
