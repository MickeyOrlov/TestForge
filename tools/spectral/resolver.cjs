// $ref resolution for linting: local files only. Spectral's default resolver fetches http(s)
// references, which would make a lint send traffic chosen by the document; here they fail instead.
const {Resolver} = require('@stoplight/spectral-ref-resolver');
const {resolveFile} = require('@stoplight/json-ref-readers');

const refuse = () => Promise.reject(new Error('remote $ref refused: lint works on local documents only'));

module.exports = new Resolver({
  resolvers: {
    file: {resolve: resolveFile},
    http: {resolve: refuse},
    https: {resolve: refuse},
  },
});
