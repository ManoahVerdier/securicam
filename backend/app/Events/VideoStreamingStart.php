<?php

namespace App\Events;

use Illuminate\Broadcasting\InteractsWithSockets;
use Illuminate\Broadcasting\PrivateChannel;
use Illuminate\Contracts\Broadcasting\ShouldBroadcastNow;
use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class VideoStreamingStart implements ShouldBroadcastNow
{
    use Dispatchable, InteractsWithSockets, SerializesModels;

    public function __construct(
        public int $cameraId
    ) {}

    public function broadcastOn(): array
    {
        return [
            new PrivateChannel("camera.{$this->cameraId}"),
        ];
    }

    public function broadcastWith(): array
    {
        return [
            'camera_id' => $this->cameraId,
            'action' => 'start_streaming',
            'timestamp' => now()->toIso8601ZuluString(),
        ];
    }

    public function broadcastAs(): string
    {
        return 'video.streaming.start';
    }
}
