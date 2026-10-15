# 從專案根目錄建置：docker compose build（或 docker build -f server/Dockerfile .）
FROM node:22-alpine
# 時區由 Node 內建的 Intl 處理，不需要額外安裝 tzdata
RUN mkdir -p /data && chown node:node /data
ENV NODE_ENV=production TZ=Asia/Taipei
WORKDIR /app/server
COPY server/package.json server/package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY server/src ./src
COPY server/db ./db
COPY prototype/core.js /app/prototype/core.js
USER node
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://127.0.0.1:8080/api/health > /dev/null || exit 1
CMD ["node", "src/index.js"]
