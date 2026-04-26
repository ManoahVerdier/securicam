<?php

namespace App\Events;

use Illuminate\Broadcasting\InteractsWithSockets;
use Illuminate\Broadcasting\PrivateChannel;
use Illuminate\Contracts\Broadcasting\ShouldBroadcastNow;
use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class SwitchCamera implements ShouldBroadcastNow
{
    use Dispatchable, InteractsWithSockets, SerializesModels;

    public function __construct(
        public int $cameraId,
        public ?string $lensId = null
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
            'lens_id' => $this->lensId,
            'action' => 'switch_camera',
            'timestamp' => now()->toIso8601String(),
        ];
    }

    public function broadcastAs(): string
    {
        return 'camera.switch';
    }
}
