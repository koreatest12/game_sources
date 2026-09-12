FROM httpd:2.4.68-alpine

RUN sed -i \
    -e 's/^Listen 80$/Listen 8080/' \
    -e 's/^#LoadModule proxy_module modules\/mod_proxy.so/LoadModule proxy_module modules\/mod_proxy.so/' \
    -e 's/^#LoadModule proxy_http_module modules\/mod_proxy_http.so/LoadModule proxy_http_module modules\/mod_proxy_http.so/' \
    /usr/local/apache2/conf/httpd.conf \
    && printf '\nInclude conf/extra/game-sources.conf\n' >> /usr/local/apache2/conf/httpd.conf

COPY game-sources.conf /usr/local/apache2/conf/extra/game-sources.conf

EXPOSE 8080

HEALTHCHECK --interval=20s --timeout=5s --start-period=10s --retries=5 \
  CMD wget -q -O - http://127.0.0.1:8080/health | grep -q '"status":"UP"' || exit 1

CMD ["httpd-foreground"]
