FROM php:8.3-fpm

# Install system dependencies
RUN apt-get update && apt-get install -y \
    git \
    curl \
    libpng-dev \
    libonig-dev \
    libxml2-dev \
    libsqlite3-dev \
    zip \
    unzip \
    && docker-php-ext-install pdo_sqlite mbstring exif pcntl bcmath gd \
    && rm -rf /var/lib/apt/lists/*

# Install Redis extension
RUN pecl install redis && docker-php-ext-enable redis

# Bump PHP upload limits so video captures (typically 5–50 MB MP4 from the
# Android recorder) are not rejected with HTTP 422 "The file failed to upload".
# The Laravel validator already caps at 100 MB; PHP must allow at least that.
RUN { \
        echo "upload_max_filesize=200M"; \
        echo "post_max_size=200M"; \
        echo "memory_limit=256M"; \
        echo "max_execution_time=120"; \
    } > /usr/local/etc/php/conf.d/securicam-uploads.ini

# Install Composer
COPY --from=composer:latest /usr/bin/composer /usr/bin/composer

# Set working directory
WORKDIR /var/www

# Copy composer files first for better layer caching
COPY composer.json composer.lock /var/www/
RUN composer install --no-interaction --no-dev --prefer-dist --no-scripts --no-autoloader

# Copy the rest of the application
COPY . /var/www

# Generate optimised autoloader
RUN composer dump-autoload --no-dev --optimize --no-scripts

# Set permissions
RUN chown -R www-data:www-data /var/www \
    && chmod -R 755 /var/www/storage

EXPOSE 9000
CMD ["php-fpm"]
