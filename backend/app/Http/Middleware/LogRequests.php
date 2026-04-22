<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Log;
use Symfony\Component\HttpFoundation\Response;

class LogRequests
{
    public function handle(Request $request, Closure $next): Response
    {
        $start = microtime(true);

        $response = $next($request);

        $duration = round((microtime(true) - $start) * 1000);
        $user     = $request->user()?->id ?? 'guest';
        $status   = $response->getStatusCode();

        Log::channel('requests')->info('HTTP', [
            'method'     => $request->method(),
            'path'       => $request->path(),
            'ip'         => $request->ip(),
            'user_id'    => $user,
            'status'     => $status,
            'duration_ms' => $duration,
            'user_agent' => $request->userAgent(),
            'body'       => $this->safeBody($request),
        ]);

        return $response;
    }

    private function safeBody(Request $request): array
    {
        $data = $request->except(['password', 'password_confirmation', 'token', 'auth']);

        // Tronquer les SDPs WebRTC qui sont très longs
        foreach (['sdp'] as $field) {
            if (isset($data[$field]) && strlen($data[$field]) > 100) {
                $data[$field] = substr($data[$field], 0, 100) . '…[truncated]';
            }
        }

        return $data;
    }
}
