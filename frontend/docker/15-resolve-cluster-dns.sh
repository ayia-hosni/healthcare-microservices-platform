#!/bin/sh
# Runs automatically as one of nginx's /docker-entrypoint.d/ hooks. nginx's `resolver`
# directive needs a literal IP (giving it a hostname is circular — you'd need a resolver to
# resolve the resolver), but the cluster DNS IP isn't known until the pod actually starts.
# Kubernetes always writes it into /etc/resolv.conf as the first nameserver, so we read it
# from there and substitute it into the placeholder left in nginx.conf.
set -eu

RESOLVER_IP=$(awk '/^nameserver/{print $2; exit}' /etc/resolv.conf)

if [ -n "$RESOLVER_IP" ]; then
  sed -i "s/__RESOLVER_IP__/${RESOLVER_IP}/" /etc/nginx/conf.d/default.conf
fi
