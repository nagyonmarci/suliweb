# Frontend Dockerfile - Astro 6 + nginx
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json .npmrc* ./
RUN npm install --legacy-peer-deps
COPY . .
RUN npm run build

FROM cgr.dev/chainguard/nginx:latest@sha256:fdd65ea225da60875e24a8b45d1e65501f9e4991b3b097ae65ed8a4c8f3782a3
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/nginx.default.conf
EXPOSE 8080
