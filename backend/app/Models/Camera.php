<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

class Camera extends Model
{
    use HasFactory;

    /**
     * The attributes that are mass assignable.
     *
     * @var array<int, string>
     */
    protected $fillable = [
        'user_id',
        'name',
        'device_id',
        'status',
        'last_seen_at',
        'last_ip',
        'connected_at',
    ];

    /**
     * Get the attributes that should be cast.
     *
     * @return array<string, string>
     */
    protected function casts(): array
    {
        return [
            'last_seen_at' => 'datetime',
            'connected_at' => 'datetime',
        ];
    }

    /**
     * Camera status constants.
     */
    public const STATUS_OFFLINE = 'offline';
    public const STATUS_ONLINE = 'online';
    public const STATUS_STREAMING = 'streaming';
    public const STATUS_RECORDING = 'recording';

    /**
     * Get the user that owns the camera.
     */
    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    /**
     * Get the captures for the camera.
     */
    public function captures(): HasMany
    {
        return $this->hasMany(Capture::class);
    }

    /**
     * Check if the camera is online.
     */
    public function isOnline(): bool
    {
        return in_array($this->status, [
            self::STATUS_ONLINE,
            self::STATUS_STREAMING,
            self::STATUS_RECORDING,
        ]);
    }

    /**
     * Check if the camera is currently recording.
     */
    public function isRecording(): bool
    {
        return $this->status === self::STATUS_RECORDING;
    }
}
