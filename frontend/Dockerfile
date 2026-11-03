FROM nginx:1.27-alpine
WORKDIR /usr/share/nginx/html

COPY dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
