# ============================================================
# STAGE 1: Build - Node 20 + Vite
# ============================================================
# Kompilujemy TypeScript + React do statycznego HTML/JS/CSS.
# ============================================================
FROM node:20-alpine AS build

WORKDIR /build

# Najpierw package.json i package-lock.json - dla cache'owania zależności
COPY package*.json ./
RUN npm ci

# Reszta źródeł
COPY . .

# Build produkcyjny do folderu dist/
RUN npm run build

# ============================================================
# STAGE 2: Runtime - nginx serwujący statyczne pliki
# ============================================================
# nginx:alpine waży ~25 MB, plus nasze ~2-3 MB statyków.
# Finalny obraz ~30 MB.
# ============================================================
FROM nginx:1.27-alpine

# Własna konfiguracja nginx (SPA routing + proxy do backendu)
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Statyczne pliki z buildu
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
