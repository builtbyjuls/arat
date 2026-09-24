const invitationTokenPath = /(\/group-invites\/)[^/?#]+/g;

const redactProxyUrl = (url) => url.replace(invitationTokenPath, '$1[redacted]');

const localBackendProxy = {
  target: 'http://localhost:8080',
  secure: false,
  changeOrigin: false,
  configure(proxy) {
    proxy.on('error', (_error, _request, response) => {
      if ('req' in response && response.req) {
        response.req.url = redactProxyUrl(response.req.url ?? '/');
      }
    });
  },
};

module.exports = {
  '/api': localBackendProxy,
  '/v3': localBackendProxy,
};
