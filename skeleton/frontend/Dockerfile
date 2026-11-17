# syntax=docker/dockerfile:1

# Build stage. @angular/build requires Node ^22.22.3 (see package-lock.json); the specs use process.getBuiltinModule.
FROM node:22.23.2-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build -- --configuration production

# Runtime stage. The application builder writes to dist/<project>/browser (project: skeleton-frontend).
FROM nginx:1.30.6-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY security-headers.conf /etc/nginx/security-headers.conf
COPY --from=build /src/dist/skeleton-frontend/browser /usr/share/nginx/html
EXPOSE 80
