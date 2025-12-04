FROM nginx:alpine

# Copy static files to nginx html directory
COPY index.html /usr/share/nginx/html/

# Expose port 80
EXPOSE 80

# nginx runs automatically as the default command