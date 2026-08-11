FROM node:22-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npx ng build --configuration production

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/15-resolve-cluster-dns.sh /docker-entrypoint.d/15-resolve-cluster-dns.sh
RUN chmod +x /docker-entrypoint.d/15-resolve-cluster-dns.sh
COPY --from=build /workspace/dist/healthcare-frontend/browser /usr/share/nginx/html
EXPOSE 80
