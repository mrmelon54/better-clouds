package com.qendolin.betterclouds.clouds;

import com.qendolin.betterclouds.Config;
import com.qendolin.betterclouds.Main;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Generator implements AutoCloseable {
    private double originX;
    private double originZ;

    private Buffer buffer;
    private final Sampler sampler = new Sampler();

    @Nullable
    private Task queuedTask;
    @Nullable
    private Task runningTask;
    @Nullable
    private Task completedTask;
    @Nullable
    private Task swappedTask;

    private Vec3d lastSortPos;
    private int lastSortTask;
    private int lastSortIdx;
    private int fullSortCount;

    private static int floorCloudChunk(double coord, int chunkSize) {
        return (int) coord / chunkSize;
    }

    public synchronized boolean canGenerate() {
        return queuedTask != null;
    }

    public synchronized boolean canSwap() {
        return completedTask != null && completedTask != swappedTask;
    }

    public synchronized boolean canRender() {
        return completedTask != null;
    }

    public synchronized void clear() {
        queuedTask = null;
        if(runningTask != null) runningTask.cancel();
        runningTask = null;
        completedTask = null;
        swappedTask = null;
    }

    public synchronized int instanceVertexCount() {
        if(swappedTask == null) return 0;
        return swappedTask.instanceVertexCount();
    }

    public synchronized double renderOriginX(double cameraX) {
        if(swappedTask == null) return 0;
        return swappedTask.chunkX() * swappedTask.options().chunkSize - cameraX + originX;
    }

    public synchronized double renderOriginZ(double cameraZ) {
        if(swappedTask == null) return 0;
        return swappedTask.chunkZ() * swappedTask.options().chunkSize - cameraZ + originZ;
    }

    public synchronized int cloudCount() {
        if(swappedTask == null) return 0;
        return swappedTask.cloudCount();
    }

    public synchronized boolean generating() {
        return runningTask != null;
    }

    @Override
    public void close() {
        buffer.close();
    }

    public void bind() {
        buffer.bind();
    }

    public void unbind() {
        buffer.unbind();
    }

    public synchronized boolean reallocate(Config options, boolean fancy) {
        int bufferSize = calcBufferSize(options);

        if(buffer.hasChanged(bufferSize, fancy, options.usePersistentBuffers)) {
            buffer.close();
            buffer = new Buffer(bufferSize, fancy, options.usePersistentBuffers);
            clear();
            return true;
        }
        return false;
    }

    public synchronized void allocate(Config options, boolean fancy) {
        int bufferSize = calcBufferSize(options);
        if(buffer != null) {
            buffer.close();
        }
        buffer = new Buffer(bufferSize, fancy, options.usePersistentBuffers);
        clear();
    }

    private static int calcBufferSize(Config options) {
        int distance = options.blockDistance();
        return MathHelper.floor(distance / options.spacing)
            + MathHelper.ceil(distance / options.spacing);
    }

    public synchronized void update(Vec3d camera, float timeDelta, Config options, float cloudiness) {
        originX -= timeDelta * options.windSpeed;
        originZ = 0;
        double worldOriginX = camera.x - this.originX;
        double worldOriginZ = camera.z - this.originZ;

        int chunkX = floorCloudChunk(worldOriginX, options.chunkSize);
        int chunkZ = floorCloudChunk(worldOriginZ, options.chunkSize);

        boolean updateGeometry;
        if(queuedTask != null || runningTask != null || completedTask != null) {
            Task prevTask = queuedTask == null ? (runningTask == null ? completedTask : runningTask) : queuedTask;
            int prevChunkX = prevTask.chunkX();
            int prevChunkZ = prevTask.chunkZ();
            boolean chunkChanged = prevChunkX != chunkX || prevChunkZ != chunkZ;

            Config prevOptions = prevTask.options();
            boolean optionsChanged = options.fuzziness != prevOptions.fuzziness
                || options.chunkSize != prevOptions.chunkSize
                || options.spreadY != prevOptions.spreadY
                || options.spacing != prevOptions.spacing
                || options.jitter != prevOptions.jitter
                || options.distance != prevOptions.distance;

            float prevCloudiness = prevTask.cloudiness();
            boolean cloudinessChanged = Math.abs(cloudiness - prevCloudiness) > 0.05;

            boolean bufferCleared = buffer.swapCount() == 0 && queuedTask == null && runningTask == null && (completedTask == null || completedTask == swappedTask);

            updateGeometry = chunkChanged || optionsChanged || cloudinessChanged || bufferCleared;
        } else {
            updateGeometry = true;
        }

        if(updateGeometry) {
            queuedTask = new Task(chunkX, chunkZ, new Config(options), cloudiness, buffer, sampler);
        }
    }

    public synchronized void generate(boolean forceSync) {
        if(queuedTask == null) {
            Main.LOGGER.warn("generate called with no queued task");
            return;
        }
        if(runningTask != null) {
            runningTask.cancel();
        }
        runningTask = queuedTask;
        queuedTask = null;

//        if(runningTask.options.async && !forceSync) {
            CompletableFuture.runAsync(runningTask::run)
            .whenComplete((unused, throwable) -> {
                synchronized (this) {
                    if(runningTask.completed()) completedTask = runningTask;
                    runningTask = null;
                }
            });
//        } else {
//            runningTask.run();
//            if(runningTask.completed()) completedTask = runningTask;
//            runningTask = null;
//        }
    }

    public synchronized void swap() {
        if(completedTask == null) {
            Main.LOGGER.warn("swap called with no completed task");
            return;
        }
        if(swappedTask == completedTask) {
            Main.LOGGER.warn("swap called with swapped task");
            return;
        }
        completedTask.buffer.swap();
        swappedTask = completedTask;
    }

    private static class Task {

        private static final AtomicInteger nextId = new AtomicInteger(1);
        private final int id;
        private final int chunkX;
        private final int chunkZ;
        private final Config options;
        private final float cloudiness;
        private final Buffer buffer;
        private final Sampler sampler;
        private final AtomicBoolean ran = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private int cloudCount;
        private CloudList cloudList;

        public Task(int chunkX, int chunkZ, Config options, float cloudiness, Buffer buffer, Sampler sampler) {
            this.id = nextId.getAndIncrement();
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.options = options;
            this.cloudiness = cloudiness;
            this.buffer = buffer;
            this.sampler = sampler;
        }

        public void cancel() {
            synchronized (this) {
                if(completed.get()) return;
                if(cancelled.getAndSet(true)) return;
                Main.LOGGER.warn("generate task cancelled");
                try {
                    wait();
                } catch (InterruptedException ignored) {}
            }
        }

        public int id() {
            return id;
        }
        public boolean completed() {
            return completed.get();
        }
        public int cloudCount() {
            return cloudCount;
        }
        public int chunkX() {
            return chunkX;
        }
        public int chunkZ() {
            return chunkZ;
        }
        public int instanceVertexCount() {
            return buffer.instanceVertexCount();
        }
        public Config options() {
            return options;
        }
        public float cloudiness() {
            return cloudiness;
        }

        public void run() {
            synchronized (this) {
                if(ran.getAndSet(true) || cancelled.get()) return;
            }

            int distance = options.blockDistance();
            double spacing = options.spacing;

            int halfGridPointsC = MathHelper.ceil(distance / spacing);
            int halfGridPointsF = MathHelper.floor(distance / spacing);

            int originX = chunkX * options.chunkSize;
            int originZ = chunkZ * options.chunkSize;
            double alignedOriginX = MathHelper.floor(originX / spacing) * spacing;
            double alignedOriginZ = MathHelper.floor(originZ / spacing) * spacing;

            buffer.clear();

            for (int gridX = -halfGridPointsF; gridX < halfGridPointsC; gridX++) {
                for (int gridZ = -halfGridPointsF; gridZ < halfGridPointsC; gridZ++) {
                    int sampleX = MathHelper.floor(gridX * spacing + alignedOriginX);
                    int sampleZ = MathHelper.floor(gridZ * spacing + alignedOriginZ);
                    float value = sampler.sample(sampleX, sampleZ, cloudiness, options.fuzziness);
                    if (value <= 0) continue;

                    float x = (float) (sampleX - chunkX * options.chunkSize + sampler.jitterX(sampleX, sampleZ) * options.jitter * spacing);
                    // TODO: cloudPointiness value
                    float y = options.spreadY * value * value;
                    float z = (float) (sampleZ - chunkZ * options.chunkSize + sampler.jitterZ(sampleX, sampleZ) * options.jitter * spacing);

                    buffer.put(x, y, z);
                    cloudCount++;
                }
                if(cancelled.get()) {
                    synchronized (this) {
                        notify();
                        return;
                    }
                }
            }

            cloudList = new CloudList(buffer.writeBuffer());
            completed.set(true);
        }
    }

    public static class CloudList implements Sort.List {

        private final FloatBuffer buffer;
        private final float[] distances;
        private float originX;
        private float originY;
        private float originZ;
        private int compares = 0;
        private int swaps = 0;

        public CloudList(FloatBuffer buffer) {
            this.distances = new float[buffer.position() / 3];
            updateOrigin(0, 0,0);
            this.buffer = buffer;
        }

        public void updateOrigin(float x, float y, float z) {
            originX = x;
            originY = y;
            originZ = z;
            Arrays.fill(distances, -1);
        }

        @Override
        public void swap(int i, int j) {
            swaps++;
            float tmpDist = distances[i];
            distances[i] = distances[j];
            distances[j] = tmpDist;

            float tmpX = buffer.get(i*3);
            float tmpY = buffer.get(i*3+1);
            float tmpZ = buffer.get(i*3+2);
            buffer.put(i*3, buffer.get(j*3));
            buffer.put(i*3+1, buffer.get(j*3+1));
            buffer.put(i*3+2, buffer.get(j*3+2));
            buffer.put(j*3, tmpX);
            buffer.put(j*3+1, tmpY);
            buffer.put(j*3+2, tmpZ);
        }

        @Override
        public int compare(int i, int j) {
            compares++;
            float distI = distances[i], distJ = distances[j];
            if(distI == -1) {
                distI = calculateDistance(i);
                distances[i] = distI;
            }
            if(distJ == -1) {
                distJ = calculateDistance(j);
                distances[j] = distJ;
            }
            return Float.compare(distJ, distI);
        }

        private float calculateDistance(int i){
//            float y = buffer.get(i*3+1);
//            return y;

            float x = buffer.get(i*3);
            float y = -100 - buffer.get(i*3+1);
            float z = buffer.get(i*3+2);

            return x*x + y*y + z*z;

//            float x = buffer.get(i*3);
//            float y = buffer.get(i*3+1);
//            float z = buffer.get(i*3+2);
//
//            float dx = originX-x;
//            float dy = originY-y;
//            float dz = originZ-z;
//
//           return dx*dx + dy*dy + dz*dz;
        }

        @Override
        public int size() {
            return distances.length;
        }
    }
}
