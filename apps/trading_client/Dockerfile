FROM nginx:1.27-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY build/web /usr/share/nginx/html
# Replace Flutter's generated cache worker with the repository-owned no-cache kill-switch.
COPY deploy/flutter_service_worker.js /usr/share/nginx/html/flutter_service_worker.js
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 CMD wget -q -O - http://127.0.0.1:8080/health || exit 1
