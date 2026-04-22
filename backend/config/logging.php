<?php

use Monolog\Handler\StreamHandler;
use Monolog\Processor\PsrLogMessageProcessor;

return [

    'default' => env('LOG_CHANNEL', 'stack'),

    'deprecations' => [
        'channel' => env('LOG_DEPRECATIONS_CHANNEL', 'null'),
        'trace' => env('LOG_DEPRECATIONS_TRACE', false),
    ],

    'channels' => [

        'stack' => [
            'driver'            => 'stack',
            'channels'          => explode(',', env('LOG_STACK', 'single')),
            'ignore_exceptions' => false,
        ],

        'single' => [
            'driver'               => 'single',
            'path'                 => storage_path('logs/laravel.log'),
            'level'                => env('LOG_LEVEL', 'debug'),
            'replace_placeholders' => true,
        ],

        'stderr' => [
            'driver'               => 'monolog',
            'level'                => env('LOG_LEVEL', 'debug'),
            'handler'              => StreamHandler::class,
            'formatter'            => Monolog\Formatter\LineFormatter::class,
            'formatter_with'       => [
                'format' => "%datetime% %channel%.%level_name%: %message% %context% %extra%\n",
            ],
            'with' => [
                'stream' => 'php://stderr',
            ],
            'processors' => [PsrLogMessageProcessor::class],
        ],

        // Channel dédié aux requêtes HTTP entrantes — visible dans `docker compose logs backend`
        'requests' => [
            'driver'               => 'monolog',
            'level'                => 'info',
            'handler'              => StreamHandler::class,
            'formatter'            => Monolog\Formatter\JsonFormatter::class,
            'with' => [
                'stream' => 'php://stderr',
            ],
            'processors' => [PsrLogMessageProcessor::class],
        ],

    ],

];
