<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class Capture extends Model
{
    use HasFactory;

    /**
     * The attributes that are mass assignable.
     *
     * @var array<int, string>
     */
    protected $fillable = [
        'camera_id',
        'type',
        'file_path',
        'thumbnail_path',
        'duration',
        'file_size',
        'captured_at',
    ];

    protected $appends = ['file_url', 'thumbnail_url'];

    /**
     * Get the attributes that should be cast.
     *
     * @return array<string, string>
     */
    protected function casts(): array
    {
        return [
            'captured_at' => 'datetime',
            'duration' => 'integer',
            'file_size' => 'integer',
        ];
    }

    /**
     * Capture type constants.
     */
    public const TYPE_PHOTO = 'photo';
    public const TYPE_VIDEO = 'video';

    /**
     * Get the camera that owns the capture.
     */
    public function camera(): BelongsTo
    {
        return $this->belongsTo(Camera::class);
    }

    /**
     * Check if this capture is a photo.
     */
    public function isPhoto(): bool
    {
        return $this->type === self::TYPE_PHOTO;
    }

    /**
     * Check if this capture is a video.
     */
    public function isVideo(): bool
    {
        return $this->type === self::TYPE_VIDEO;
    }

    /**
     * Get the full URL for the file.
     */
    public function getFileUrlAttribute(): ?string
    {
        if (!$this->file_path) {
            return null;
        }

        return asset('storage/' . $this->file_path);
    }

    /**
     * Get the full URL for the thumbnail.
     */
    public function getThumbnailUrlAttribute(): ?string
    {
        if (!$this->thumbnail_path) {
            return null;
        }

        return asset('storage/' . $this->thumbnail_path);
    }
}
