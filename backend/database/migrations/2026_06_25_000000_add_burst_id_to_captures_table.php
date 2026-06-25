<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::table('captures', function (Blueprint $table) {
            $table->string('burst_id', 36)->nullable()->after('type')->index();
        });
    }

    public function down(): void
    {
        Schema::table('captures', function (Blueprint $table) {
            $table->dropIndex(['burst_id']);
            $table->dropColumn('burst_id');
        });
    }
};
