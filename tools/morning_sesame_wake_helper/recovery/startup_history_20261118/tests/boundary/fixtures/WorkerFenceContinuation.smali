.class final Ldev/yossi/morningsesame/WorkerFenceContinuation;
.super Ljava/lang/Object;
.source "WorkerFenceContinuation.java"

# interfaces
.implements Ljava/lang/Runnable;


# static fields
.field private static final CONFIRM:I = 0x1

.field private static final DEFERRED:Ljava/lang/ThreadLocal;
    .annotation system Ldalvik/annotation/Signature;
        value = {
            "Ljava/lang/ThreadLocal<",
            "Ldev/yossi/morningsesame/WorkerFenceContinuation;",
            ">;"
        }
    .end annotation
.end field

.field private static final MAIN:Landroid/os/Handler;

.field private static final PLAYBACK:I


# instance fields
.field private final context:Landroid/content/Context;

.field private deadlineElapsed:J

.field private finished:Z

.field private minimumSampleWall:J

.field private final operation:I

.field private releasedElapsed:J

.field private final runId:Ljava/lang/String;

.field private final videoId:Ljava/lang/String;


# direct methods
.method static constructor <clinit>()V
    .registers 2

    .line 13
    new-instance v0, Landroid/os/Handler;

    invoke-static {}, Landroid/os/Looper;->getMainLooper()Landroid/os/Looper;

    move-result-object v1

    invoke-direct {v0, v1}, Landroid/os/Handler;-><init>(Landroid/os/Looper;)V

    sput-object v0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->MAIN:Landroid/os/Handler;

    .line 14
    new-instance v0, Ljava/lang/ThreadLocal;

    invoke-direct {v0}, Ljava/lang/ThreadLocal;-><init>()V

    sput-object v0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->DEFERRED:Ljava/lang/ThreadLocal;

    return-void
.end method

.method private constructor <init>(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;I)V
    .registers 5

    .line 26
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    .line 27
    invoke-virtual {p1}, Landroid/content/Context;->getApplicationContext()Landroid/content/Context;

    move-result-object p1

    iput-object p1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->context:Landroid/content/Context;

    .line 28
    iput-object p2, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->runId:Ljava/lang/String;

    .line 29
    iput-object p3, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->videoId:Ljava/lang/String;

    .line 30
    iput p4, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->operation:I

    .line 31
    return-void
.end method

.method private afterRelease()V
    .registers 8

    .line 60
    :try_start_0
    const-class v0, Ldev/yossi/morningsesame/RunCoordinator;

    monitor-enter v0
    :try_end_3
    .catch Ljava/lang/RuntimeException; {:try_start_0 .. :try_end_3} :catch_43

    .line 61
    :try_start_3
    iget-object v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->context:Landroid/content/Context;

    iget-object v2, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->runId:Ljava/lang/String;

    invoke-static {v1, v2}, Ldev/yossi/morningsesame/RunCoordinator;->checkpoint(Landroid/content/Context;Ljava/lang/String;)Z

    move-result v1

    if-nez v1, :cond_12

    .line 62
    const/4 v1, 0x1

    iput-boolean v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->finished:Z

    .line 63
    monitor-exit v0

    return-void

    .line 65
    :cond_12
    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v1

    iput-wide v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->releasedElapsed:J

    .line 66
    invoke-static {}, Ljava/lang/System;->currentTimeMillis()J

    move-result-wide v1

    iput-wide v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->minimumSampleWall:J

    .line 67
    iget-wide v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->releasedElapsed:J

    iget-object v3, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->context:Landroid/content/Context;

    iget-object v4, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->runId:Ljava/lang/String;

    invoke-static {v3, v4}, Ldev/yossi/morningsesame/RunCoordinator;->remaining(Landroid/content/Context;Ljava/lang/String;)J

    move-result-wide v3

    const-wide/16 v5, 0xbb8

    invoke-static {v5, v6, v3, v4}, Ljava/lang/Math;->min(JJ)J

    move-result-wide v3

    add-long/2addr v1, v3

    iput-wide v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->deadlineElapsed:J

    .line 68
    sget-object v1, Ldev/yossi/morningsesame/WorkerFenceContinuation;->MAIN:Landroid/os/Handler;

    invoke-virtual {v1, p0}, Landroid/os/Handler;->post(Ljava/lang/Runnable;)Z

    move-result v1

    if-nez v1, :cond_3e

    const-string v1, "startup_worker_fence_unavailable"

    invoke-direct {p0, v1}, Ldev/yossi/morningsesame/WorkerFenceContinuation;->cancel(Ljava/lang/String;)V

    .line 69
    :cond_3e
    monitor-exit v0

    .line 72
    goto :goto_49

    .line 69
    :catchall_40
    move-exception v1

    monitor-exit v0
    :try_end_42
    .catchall {:try_start_3 .. :try_end_42} :catchall_40

    :try_start_42
    throw v1
    :try_end_43
    .catch Ljava/lang/RuntimeException; {:try_start_42 .. :try_end_43} :catch_43

    .line 70
    :catch_43
    move-exception v0

    .line 71
    const-string v0, "startup_worker_fence_unavailable"

    invoke-direct {p0, v0}, Ldev/yossi/morningsesame/WorkerFenceContinuation;->cancel(Ljava/lang/String;)V

    .line 73
    :goto_49
    return-void
.end method

.method private cancel(Ljava/lang/String;)V
    .registers 7

    .line 115
    const-string v0, "cancelled"

    iget-boolean v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->finished:Z

    if-eqz v1, :cond_7

    return-void

    .line 116
    :cond_7
    const/4 v1, 0x1

    iput-boolean v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->finished:Z

    .line 118
    :try_start_a
    iget-object v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->context:Landroid/content/Context;

    new-instance v2, Landroid/content/Intent;

    const-string v3, "dev.yossi.onnkeyguard.MORNING_STARTUP_AUTH"

    invoke-direct {v2, v3}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V

    const-string v3, "dev.yossi.onnkeyguard"

    const-string v4, "dev.yossi.onnkeyguard.MorningPlaybackReceiver"

    .line 119
    invoke-virtual {v2, v3, v4}, Landroid/content/Intent;->setClassName(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;

    move-result-object v2

    const-string v3, "command"

    const-string v4, "cancel"

    .line 120
    invoke-virtual {v2, v3, v4}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;

    move-result-object v2

    const-string v3, "run_generation"

    iget-object v4, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->runId:Ljava/lang/String;

    invoke-virtual {v2, v3, v4}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;

    move-result-object v2

    .line 118
    invoke-virtual {v1, v2}, Landroid/content/Context;->sendBroadcast(Landroid/content/Intent;)V
    :try_end_2e
    .catch Ljava/lang/RuntimeException; {:try_start_a .. :try_end_2e} :catch_4c
    .catchall {:try_start_a .. :try_end_2e} :catchall_39

    .line 124
    iget-object v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->context:Landroid/content/Context;

    iget-object v2, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->runId:Ljava/lang/String;

    invoke-static {v1, v2}, Ldev/yossi/morningsesame/RunCoordinator;->isActive(Landroid/content/Context;Ljava/lang/String;)Z

    move-result v1

    if-eqz v1, :cond_5e

    .line 125
    goto :goto_57

    .line 124
    :catchall_39
    move-exception v1

    iget-object v2, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->context:Landroid/content/Context;

    iget-object v3, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->runId:Ljava/lang/String;

    invoke-static {v2, v3}, Ldev/yossi/morningsesame/RunCoordinator;->isActive(Landroid/content/Context;Ljava/lang/String;)Z

    move-result v2

    if-eqz v2, :cond_4b

    .line 125
    iget-object v2, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->context:Landroid/content/Context;

    iget-object v3, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->runId:Ljava/lang/String;

    invoke-static {v2, v3, v0, p1}, Ldev/yossi/morningsesame/RunCoordinator;->finish(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V

    .line 127
    :cond_4b
    throw v1

    .line 121
    :catch_4c
    move-exception v1

    .line 124
    iget-object v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->context:Landroid/content/Context;

    iget-object v2, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->runId:Ljava/lang/String;

    invoke-static {v1, v2}, Ldev/yossi/morningsesame/RunCoordinator;->isActive(Landroid/content/Context;Ljava/lang/String;)Z

    move-result v1

    if-eqz v1, :cond_5e

    .line 125
    :goto_57
    iget-object v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->context:Landroid/content/Context;

    iget-object v2, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->runId:Ljava/lang/String;

    invoke-static {v1, v2, v0, p1}, Ldev/yossi/morningsesame/RunCoordinator;->finish(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V

    .line 128
    :cond_5e
    return-void
.end method

.method private static defer(Ldev/yossi/morningsesame/WorkerFenceContinuation;)V
    .registers 2

    .line 42
    sget-object v0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->DEFERRED:Ljava/lang/ThreadLocal;

    invoke-virtual {v0}, Ljava/lang/ThreadLocal;->get()Ljava/lang/Object;

    move-result-object v0

    if-nez v0, :cond_e

    .line 43
    sget-object v0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->DEFERRED:Ljava/lang/ThreadLocal;

    invoke-virtual {v0, p0}, Ljava/lang/ThreadLocal;->set(Ljava/lang/Object;)V

    .line 44
    return-void

    .line 42
    :cond_e
    new-instance p0, Ljava/lang/IllegalStateException;

    const-string v0, "duplicate_worker_result"

    invoke-direct {p0, v0}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw p0
.end method

.method static deferConfirmation(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)V
    .registers 5

    .line 38
    new-instance v0, Ldev/yossi/morningsesame/WorkerFenceContinuation;

    const/4 v1, 0x1

    invoke-direct {v0, p0, p1, p2, v1}, Ldev/yossi/morningsesame/WorkerFenceContinuation;-><init>(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;I)V

    invoke-static {v0}, Ldev/yossi/morningsesame/WorkerFenceContinuation;->defer(Ldev/yossi/morningsesame/WorkerFenceContinuation;)V

    .line 39
    return-void
.end method

.method static deferPlayback(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)V
    .registers 5

    .line 34
    new-instance v0, Ldev/yossi/morningsesame/WorkerFenceContinuation;

    const/4 v1, 0x0

    invoke-direct {v0, p0, p1, p2, v1}, Ldev/yossi/morningsesame/WorkerFenceContinuation;-><init>(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;I)V

    invoke-static {v0}, Ldev/yossi/morningsesame/WorkerFenceContinuation;->defer(Ldev/yossi/morningsesame/WorkerFenceContinuation;)V

    .line 35
    return-void
.end method

.method static finishReceiver(Landroid/content/BroadcastReceiver$PendingResult;)V
    .registers 3

    .line 47
    sget-object v0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->DEFERRED:Ljava/lang/ThreadLocal;

    invoke-virtual {v0}, Ljava/lang/ThreadLocal;->get()Ljava/lang/Object;

    move-result-object v0

    check-cast v0, Ldev/yossi/morningsesame/WorkerFenceContinuation;

    .line 48
    sget-object v1, Ldev/yossi/morningsesame/WorkerFenceContinuation;->DEFERRED:Ljava/lang/ThreadLocal;

    invoke-virtual {v1}, Ljava/lang/ThreadLocal;->remove()V

    .line 50
    :try_start_d
    invoke-virtual {p0}, Landroid/content/BroadcastReceiver$PendingResult;->finish()V
    :try_end_10
    .catch Ljava/lang/RuntimeException; {:try_start_d .. :try_end_10} :catch_17

    .line 54
    nop

    .line 55
    if-eqz v0, :cond_16

    invoke-direct {v0}, Ldev/yossi/morningsesame/WorkerFenceContinuation;->afterRelease()V

    .line 56
    :cond_16
    return-void

    .line 51
    :catch_17
    move-exception p0

    .line 52
    if-eqz v0, :cond_1f

    const-string v1, "startup_worker_release_failed"

    invoke-direct {v0, v1}, Ldev/yossi/morningsesame/WorkerFenceContinuation;->cancel(Ljava/lang/String;)V

    .line 53
    :cond_1f
    throw p0
.end method


# virtual methods
.method public run()V
    .registers 15

    .line 76
    iget-boolean v0, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->finished:Z

    if-eqz v0, :cond_5

    return-void

    .line 78
    :cond_5
    :try_start_5
    const-class v0, Ldev/yossi/morningsesame/RunCoordinator;

    monitor-enter v0
    :try_end_8
    .catch Ljava/lang/RuntimeException; {:try_start_5 .. :try_end_8} :catch_ae

    .line 79
    :try_start_8
    iget-object v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->context:Landroid/content/Context;

    iget-object v2, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->runId:Ljava/lang/String;

    invoke-static {v1, v2}, Ldev/yossi/morningsesame/RunCoordinator;->checkpoint(Landroid/content/Context;Ljava/lang/String;)Z

    move-result v1

    const/4 v2, 0x1

    if-nez v1, :cond_17

    .line 80
    iput-boolean v2, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->finished:Z

    .line 81
    monitor-exit v0

    return-void

    .line 83
    :cond_17
    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v12

    .line 84
    iget-wide v3, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->deadlineElapsed:J

    cmp-long v1, v12, v3

    if-ltz v1, :cond_28

    .line 85
    const-string v1, "startup_worker_fence_deadline"

    invoke-direct {p0, v1}, Ldev/yossi/morningsesame/WorkerFenceContinuation;->cancel(Ljava/lang/String;)V

    .line 86
    monitor-exit v0

    return-void

    .line 88
    :cond_28
    iget-object v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->context:Landroid/content/Context;

    iget-object v3, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->runId:Ljava/lang/String;

    invoke-static {v1, v3}, Ldev/yossi/morningsesame/RunCoordinator;->isStartupBound(Landroid/content/Context;Ljava/lang/String;)Z

    move-result v1

    .line 89
    xor-int/2addr v1, v2

    if-nez v1, :cond_76

    .line 90
    iget-object v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->context:Landroid/content/Context;

    invoke-static {v1}, Ldev/yossi/morningsesame/RunCoordinator;->prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;

    move-result-object v1

    .line 91
    const-string v3, "startup_fence_received_elapsed"

    const-wide/16 v4, -0x1

    invoke-interface {v1, v3, v4, v5}, Landroid/content/SharedPreferences;->getLong(Ljava/lang/String;J)J

    move-result-wide v6

    .line 92
    const-string v3, "startup_fence_sample_wall"

    invoke-interface {v1, v3, v4, v5}, Landroid/content/SharedPreferences;->getLong(Ljava/lang/String;J)J

    move-result-wide v3

    iget-wide v8, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->minimumSampleWall:J

    cmp-long v3, v3, v8

    if-ltz v3, :cond_75

    iget-wide v3, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->releasedElapsed:J

    cmp-long v3, v6, v3

    if-ltz v3, :cond_75

    const-string v3, "startup_fence_state"

    const-string v4, "unknown"

    .line 95
    invoke-interface {v1, v3, v4}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object v3

    invoke-static {v3}, Ldev/yossi/morningsesame/StartupFencePolicy;->parse(Ljava/lang/String;)Ldev/yossi/morningsesame/StartupFencePolicy$State;

    move-result-object v3

    const-string v4, "startup_fence_deadline_wall"

    .line 96
    const-wide/16 v8, 0x0

    invoke-interface {v1, v4, v8, v9}, Landroid/content/SharedPreferences;->getLong(Ljava/lang/String;J)J

    move-result-wide v8

    .line 97
    invoke-static {}, Ljava/lang/System;->currentTimeMillis()J

    move-result-wide v10

    .line 94
    move-wide v4, v6

    move-wide v6, v12

    invoke-static/range {v3 .. v11}, Ldev/yossi/morningsesame/StartupFencePolicy;->permits(Ldev/yossi/morningsesame/StartupFencePolicy$State;JJJJ)Z

    move-result v1

    if-eqz v1, :cond_75

    move v1, v2

    goto :goto_76

    :cond_75
    const/4 v1, 0x0

    .line 99
    :cond_76
    :goto_76
    if-eqz v1, :cond_93

    .line 100
    iget v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->operation:I

    if-nez v1, :cond_86

    iget-object v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->context:Landroid/content/Context;

    iget-object v3, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->runId:Ljava/lang/String;

    iget-object v4, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->videoId:Ljava/lang/String;

    invoke-static {v1, v3, v4}, Ldev/yossi/morningsesame/AlarmReceiver;->routePlayback(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)V

    goto :goto_8f

    .line 101
    :cond_86
    iget-object v1, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->context:Landroid/content/Context;

    iget-object v3, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->runId:Ljava/lang/String;

    iget-object v4, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->videoId:Ljava/lang/String;

    invoke-static {v1, v3, v4}, Ldev/yossi/morningsesame/RunCoordinator;->confirmPlayed(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)V

    .line 102
    :goto_8f
    iput-boolean v2, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->finished:Z

    .line 103
    monitor-exit v0

    return-void

    .line 105
    :cond_93
    sget-object v1, Ldev/yossi/morningsesame/WorkerFenceContinuation;->MAIN:Landroid/os/Handler;

    iget-wide v2, p0, Ldev/yossi/morningsesame/WorkerFenceContinuation;->deadlineElapsed:J

    sub-long/2addr v2, v12

    const-wide/16 v4, 0x32

    invoke-static {v4, v5, v2, v3}, Ljava/lang/Math;->min(JJ)J

    move-result-wide v2

    invoke-virtual {v1, p0, v2, v3}, Landroid/os/Handler;->postDelayed(Ljava/lang/Runnable;J)Z

    move-result v1

    if-nez v1, :cond_a9

    .line 106
    const-string v1, "startup_worker_fence_unavailable"

    invoke-direct {p0, v1}, Ldev/yossi/morningsesame/WorkerFenceContinuation;->cancel(Ljava/lang/String;)V

    .line 108
    :cond_a9
    monitor-exit v0

    .line 111
    goto :goto_b4

    .line 108
    :catchall_ab
    move-exception v1

    monitor-exit v0
    :try_end_ad
    .catchall {:try_start_8 .. :try_end_ad} :catchall_ab

    :try_start_ad
    throw v1
    :try_end_ae
    .catch Ljava/lang/RuntimeException; {:try_start_ad .. :try_end_ae} :catch_ae

    .line 109
    :catch_ae
    move-exception v0

    .line 110
    const-string v0, "startup_worker_fence_unavailable"

    invoke-direct {p0, v0}, Ldev/yossi/morningsesame/WorkerFenceContinuation;->cancel(Ljava/lang/String;)V

    .line 112
    :goto_b4
    return-void
.end method
