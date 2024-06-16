package ca.spottedleaf.moonrise.mixin.chunk_system;

import ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import ca.spottedleaf.moonrise.patches.chunk_system.world.ChunkSystemServerChunkCache;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin extends ChunkSource implements ChunkSystemServerChunkCache {

    @Shadow
    @Final
    public ServerChunkCache.MainThreadExecutor mainThreadProcessor;


    @Shadow
    @Final
    public ServerLevel level;

    @Unique
    private final ConcurrentLong2ReferenceChainedHashTable<LevelChunk> fullChunks = new ConcurrentLong2ReferenceChainedHashTable<>();

    @Override
    public final void moonrise$setFullChunk(final int chunkX, final int chunkZ, final LevelChunk chunk) {
        final long key = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        if (chunk == null) {
            this.fullChunks.remove(key);
        } else {
            this.fullChunks.put(key, chunk);
        }
    }

    @Override
    public final LevelChunk moonrise$getFullChunkIfLoaded(final int chunkX, final int chunkZ) {
        return this.fullChunks.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }

    @Unique
    private ChunkAccess syncLoad(final int chunkX, final int chunkZ, final ChunkStatus toStatus) {
        final ChunkTaskScheduler chunkTaskScheduler = ((ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler();
        final CompletableFuture<ChunkAccess> completable = new CompletableFuture<>();
        chunkTaskScheduler.scheduleChunkLoad(
                chunkX, chunkZ, toStatus, true, PrioritisedExecutor.Priority.BLOCKING,
                completable::complete
        );

        if (TickThread.isTickThreadFor(this.level, chunkX, chunkZ)) {
            ChunkTaskScheduler.pushChunkWait(this.level, chunkX, chunkZ);
            this.mainThreadProcessor.managedBlock(completable::isDone);
            ChunkTaskScheduler.popChunkWait();
        }

        final ChunkAccess ret = completable.join();
        if (ret == null) {
            throw new IllegalStateException("Chunk not loaded when requested");
        }

        return ret;
    }

    @Unique
    private ChunkAccess getChunkFallback(final int chunkX, final int chunkZ, final ChunkStatus toStatus,
                                         final boolean load) {
        final ChunkTaskScheduler chunkTaskScheduler = ((ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler();
        final ChunkHolderManager chunkHolderManager = chunkTaskScheduler.chunkHolderManager;

        final NewChunkHolder currentChunk = chunkHolderManager.getChunkHolder(CoordinateUtils.getChunkKey(chunkX, chunkZ));

        final ChunkAccess ifPresent = currentChunk == null ? null : currentChunk.getChunkIfPresent(toStatus);

        if (ifPresent != null && (toStatus != ChunkStatus.FULL || currentChunk.isFullChunkReady())) {
            return ifPresent;
        }

        return load ? this.syncLoad(chunkX, chunkZ, toStatus) : null;
    }

    /**
     * @reason Optimise impl and support new chunk system
     * @author Spottedleaf
     */
    @Override
    @Overwrite
    public ChunkAccess getChunk(final int chunkX, final int chunkZ, final ChunkStatus toStatus,
                                final boolean load) {
        if (toStatus == ChunkStatus.FULL) {
            final LevelChunk ret = this.fullChunks.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));

            if (ret != null) {
                return ret;
            }

            return load ? this.getChunkFallback(chunkX, chunkZ, toStatus, load) : null;
        }

        return this.getChunkFallback(chunkX, chunkZ, toStatus, load);
    }

    /**
     * @reason Support new chunk system
     * @author Spottedleaf
     */
    @Override
    @Overwrite
    public LevelChunk getChunkNow(final int chunkX, final int chunkZ) {
        return ((ChunkSystemServerLevel)this.level).moonrise$getFullChunkIfLoaded(chunkX, chunkZ);
    }

    /**
     * @reason Support new chunk system
     * @author Spottedleaf
     */
    @Override
    @Overwrite
    public boolean hasChunk(final int chunkX, final int chunkZ) {
        return this.getChunkNow(chunkX, chunkZ) != null;
    }

    /**
     * @reason Support new chunk system
     * @author Spottedleaf
     */
    @Overwrite
    public CompletableFuture<ChunkResult<ChunkAccess>> getChunkFutureMainThread(final int chunkX, final int chunkZ,
                                                                                final ChunkStatus toStatus,
                                                                                final boolean create) {
        TickThread.ensureTickThread(this.level, chunkX, chunkZ, "Scheduling chunk load off-main");

        final int minLevel = ChunkLevel.byStatus(toStatus);
        final NewChunkHolder chunkHolder = ((ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(chunkX, chunkZ);

        final boolean needsFullScheduling = toStatus == ChunkStatus.FULL && (chunkHolder == null || !chunkHolder.getChunkStatus().isOrAfter(FullChunkStatus.FULL));

        if ((chunkHolder == null || chunkHolder.getTicketLevel() > minLevel || needsFullScheduling) && !create) {
            return ChunkHolder.UNLOADED_CHUNK_FUTURE;
        }

        final ChunkAccess ifPresent = chunkHolder == null ? null : chunkHolder.getChunkIfPresent(toStatus);
        if (needsFullScheduling || ifPresent == null) {
            // schedule
            final CompletableFuture<ChunkResult<ChunkAccess>> ret = new CompletableFuture<>();
            final Consumer<ChunkAccess> complete = (ChunkAccess chunk) -> {
                if (chunk == null) {
                    ret.complete(ChunkHolder.UNLOADED_CHUNK);
                } else {
                    ret.complete(ChunkResult.of(chunk));
                }
            };

            ((ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().scheduleChunkLoad(
                chunkX, chunkZ, toStatus, true,
                PrioritisedExecutor.Priority.HIGHER,
                complete
            );

            return ret;
        } else {
            // can return now
            return CompletableFuture.completedFuture(ChunkResult.of(ifPresent));
        }
    }

    /**
     * @reason Support new chunk system
     * @author Spottedleaf
     */
    @Override
    @Overwrite
    public LightChunk getChunkForLighting(final int chunkX, final int chunkZ) {
        final NewChunkHolder newChunkHolder = ((ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(chunkX, chunkZ);
        if (newChunkHolder == null) {
            return null;
        }
        return newChunkHolder.getChunkIfPresentUnchecked(ChunkStatus.INITIALIZE_LIGHT.getParent());
    }

    /**
     * @reason Support new chunk system
     * @author Spottedleaf
     */
    @Overwrite
    public boolean runDistanceManagerUpdates() {
        return ((ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.processTicketUpdates();
    }

    /**
     * @reason Support new chunk system
     * @author Spottedleaf
     */
    @Overwrite
    public boolean isPositionTicking(final long los) {
        final NewChunkHolder newChunkHolder = ((ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(los);
        return newChunkHolder != null && newChunkHolder.isTickingReady();
    }

    /**
     * @reason Support new chunk system
     * @author Spottedleaf
     */
    @Override
    @Overwrite
    public void close() throws IOException {
        ((ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.close(true, true);
    }

    /**
     * @reason Add hook to tick player chunk loader
     * @author Spottedleaf
     */
    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;tickChunks()V"
            )
    )
    private void tickHook(final CallbackInfo ci) {
        ((ChunkSystemServerLevel)this.level).moonrise$getPlayerChunkLoader().tick();
    }

    /**
     * @reason Support new chunk system
     * @author Spottedleaf
     */
    @Overwrite
    public void getFullChunk(final long pos, final Consumer<LevelChunk> consumer) {
        final LevelChunk fullChunk = this.getChunkNow(CoordinateUtils.getChunkX(pos), CoordinateUtils.getChunkZ(pos));
        if (fullChunk != null) {
            consumer.accept(fullChunk);
        }
    }

    /**
     * @reason Do not run distance manager updates on save. They are not required to run in the new chunk system.
     * Additionally, distance manager updates may not complete if some error has occurred in the propagator
     * code or there is deadlock. Thus, on shutdown we want to avoid stalling.
     * @author Spottedleaf
     */
    @Redirect(
            method = "save",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;runDistanceManagerUpdates()Z"
            )
    )
    private boolean skipSaveTicketUpdates(final ServerChunkCache instance) {
        return false;
    }
}
