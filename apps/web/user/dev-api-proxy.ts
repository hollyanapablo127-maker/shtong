import type {ClientRequest, IncomingMessage} from 'node:http'

export function forwardDevOrigin(
  proxyRequest: Pick<ClientRequest, 'setHeader'>,
  request: Pick<IncomingMessage, 'headers'>,
  target: string,
) {
  const {host, origin} = request.headers
  // Translate only this local page's same-origin request; never trust a foreign Origin.
  if (host && /^localhost:\d+$/.test(host) && origin === `http://${host}`) {
    proxyRequest.setHeader('Origin', new URL(target).origin)
  }
}
