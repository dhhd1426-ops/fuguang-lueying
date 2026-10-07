FROM node:22-alpine
WORKDIR /app
COPY public ./public
COPY deploy-mainland/server.mjs deploy-mainland/password-gate.mjs ./deploy-mainland/
USER node
EXPOSE 3000
ENV HOST=0.0.0.0 PORT=3000
CMD ["node", "deploy-mainland/server.mjs"]
