# Upstream runtime helper

`url.js` is the unmodified Grafana k6 URL helper from
<https://jslib.k6.io/url/1.0.0/index.js>.

SHA-256: `43812073b0b2fdb09edd506098ea9247d6d16c373894d8e843864e5df813dddd`.

Upstream project: <https://github.com/grafana/k6-jslib-url>.
Its Apache-2.0 license is retained in `LICENSE.url`; bundled source notices are retained
in the unmodified file. This local copy avoids fetching runtime code from a CDN when
k6 executes a prepared scenario. The generated client itself remains upstream output;
preparation changes only its helper import to the local path and records that change.
