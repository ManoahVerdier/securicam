<?php

namespace App\Console\Commands;

use App\Events\CameraOffline;
use App\Models\Camera;
use Illuminate\Console\Command;
use Illuminate\Support\Carbon;

class DetectOfflineCameras extends Command
{
    protected $signature   = 'cameras:detect-offline';
    protected $description = 'Mark cameras offline when heartbeat has been absent too long.';

    public function handle(): int
    {
        $threshold = (int) config('cameras.offline_threshold_seconds', 120);
        $cutoff    = Carbon::now()->subSeconds($threshold);

        Camera::query()
            ->where('status', '!=', Camera::STATUS_OFFLINE)
            ->where(function ($q) use ($cutoff) {
                $q->whereNull('last_seen_at')
                  ->orWhere('last_seen_at', '<', $cutoff);
            })
            ->select(['id'])
            ->chunkById(100, function ($cameras) {
                foreach ($cameras as $camera) {
                    Camera::where('id', $camera->id)->update([
                        'status'       => Camera::STATUS_OFFLINE,
                        'connected_at' => null,
                    ]);
                    try {
                        broadcast(new CameraOffline($camera->id));
                    } catch (\Throwable $e) {
                        $this->warn("Broadcast failed for camera {$camera->id}: {$e->getMessage()}");
                    }
                }
            });

        return self::SUCCESS;
    }
}
