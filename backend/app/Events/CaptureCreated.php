<?php

namespace App\Events;

use App\Models\Capture;
use Illuminate\Broadcasting\InteractsWithSockets;
use Illuminate\Broadcasting\PrivateChannel;
use Illuminate\Contracts\Broadcasting\ShouldBroadcastNow;
use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

/**
 * Fired when the Android app uploads a freshly captured photo or video.
 * The frontend gallery component listens to this on the camera channel
 * to refresh in real time without polling.
 */
class CaptureCreated implements ShouldBroadcastNow
{
    use Dispatchable, InteractsWithSockets, SerializesModels;

    public function __construct(
        public Capture $capture
    ) {}

    public function broadcastOn(): array
    {
        return [
            new PrivateChannel("camera.{$this->capture->camera_id}"),
        ];
    }

    public function broadcastWith(): array
    {
        return [
            'capture' => [
                'id'             => $this->capture->id,
                'camera_id'      => $this->capture->camera_id,
                'type'           => $this->capture->type,
                'file_path'      => $this->capture->file_path,
                'thumbnail_path' => $this->capture->thumbnail_path,
                'file_url'       => $this->capture->file_url ?? null,
                'thumbnail_url'  => $this->capture->thumbnail_url ?? null,
                'duration'       => $this->capture->duration,
                'file_size'      => $this->capture->file_size,
                'captured_at'    => optional($this->capture->captured_at)->toIso8601String(),
                'created_at'     => optional($this->capture->created_at)->toIso8601String(),
            ],
        ];
    }

    public function broadcastAs(): string
    {
        return 'capture.created';
    }
}
