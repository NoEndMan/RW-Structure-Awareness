package net.flameslight.rwstructuresawareness.mixins.roadweaver;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.flameslight.rwstructuresawareness.roadweaver.RoadWeaverAccessor;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.persistence.files.RoadFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every road RoadWeaver saves goes through here: addRoad for new roads, replaceRoad for roads
 * RoadSnapService reshaped, deleteRoad, and clearAll for a wipe. Forwards each so the road maps stay
 * current. RoadWeaver releases its storage lock before returning, so nothing here runs under it.
 */
@Mixin(value = RoadFileStorage.class, remap = false)
public abstract class RoadFileStorageMixin {
    /**
     * At RoadFileStorage.addRoad RETURN: forwards the new road.
     *
     * @param level the level
     * @param road  the saved road
     * @param ci    addRoad's callback
     */
    @Inject(method = "addRoad", at = @At("RETURN"))
    private static void rwstructuresawareness$onRoadAdded(ServerLevel level, RoadData road, CallbackInfo ci) {
        RoadWeaverAccessor.onRoadSaved(level, road);
    }

    /**
     * Wraps RoadFileStorage.replaceRoad: the old shape is read before it is overwritten, so the regions it
     * covered can be refreshed.
     *
     * @param level          the level
     * @param oldFingerprint the replaced road's fingerprint
     * @param newRoad        the replacing road
     * @param original       replaceRoad
     */
    @WrapMethod(method = "replaceRoad")
    private static void rwstructuresawareness$onRoadReplaced(ServerLevel level, long oldFingerprint, RoadData newRoad,
                                                             Operation<Void> original) {
        RoadData oldRoad = RoadWeaverAccessor.loadStoredRoad(level, oldFingerprint);
        original.call(level, oldFingerprint, newRoad);
        RoadWeaverAccessor.onRoadReplaced(level, oldRoad, newRoad);
    }

    /**
     * Wraps RoadFileStorage.deleteRoad: the old shape is read before it is deleted, so the regions it covered
     * can be refreshed.
     *
     * @param level       the level
     * @param fingerprint the deleted road's fingerprint
     * @param original    deleteRoad
     */
    @WrapMethod(method = "deleteRoad")
    private static void rwstructuresawareness$onRoadDeleted(ServerLevel level, long fingerprint,
                                                            Operation<Void> original) {
        RoadData oldRoad = RoadWeaverAccessor.loadStoredRoad(level, fingerprint);
        original.call(level, fingerprint);
        RoadWeaverAccessor.onRoadReplaced(level, oldRoad, null);
    }

    /**
     * At RoadFileStorage.clearAll RETURN: forwards the wipe.
     *
     * @param level the level
     * @param ci    clearAll's callback
     */
    @Inject(method = "clearAll", at = @At("RETURN"))
    private static void rwstructuresawareness$onRoadsCleared(ServerLevel level, CallbackInfo ci) {
        RoadWeaverAccessor.onRoadsCleared(level);
    }
}
