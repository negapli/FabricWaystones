package wraith.waystones.mixin;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wraith.waystones.Waystones;
import wraith.waystones.access.PlayerEntityMixinAccess;
import wraith.waystones.block.WaystoneBlockEntity;
import wraith.waystones.client.ClientStuff;
import wraith.waystones.integration.event.WaystoneEvents;
import wraith.waystones.util.Config;
import wraith.waystones.util.WaystonePacketHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(PlayerEntity.class)
public class PlayerEntityMixin implements PlayerEntityMixinAccess {

    private final Set<String> discoveredWaystones = ConcurrentHashMap.newKeySet();
    private boolean viewDiscoveredWaystones = true;
    private boolean viewGlobalWaystones = true;
    private int teleportCooldown = 0;

    private PlayerEntity _this() {
        return (PlayerEntity) (Object) this;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    public void tick(CallbackInfo ci) {
        if (teleportCooldown <= 0) {
            return;
        }
        teleportCooldown = Math.max(0, teleportCooldown - 1);
    }

    @Inject(method = "applyDamage", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;applyArmorToDamage(Lnet/minecraft/entity/damage/DamageSource;F)F"))
    public void applyDamage(DamageSource source, float amount, CallbackInfo ci) {
        if (source == DamageSource.OUT_OF_WORLD) {
            return;
        }
        setTeleportCooldown(Config.getInstance().getCooldownWhenHurt());
    }

    @Override
    public int getTeleportCooldown() {
        return teleportCooldown;
    }

    @Override
    public void setTeleportCooldown(int cooldown) {
        if (cooldown > 0) {
            this.teleportCooldown = cooldown;
        }
    }

    @Override
    public void discoverWaystone(WaystoneBlockEntity waystone) {
        discoverWaystone(waystone.getHash());
    }

    @Override
    public void discoverWaystone(String hash) {
        discoverWaystone(hash, true);
    }

    @Override
    public void discoverWaystone(String hash, boolean sync) {
        WaystoneEvents.DISCOVER_WAYSTONE_EVENT.invoker().onUpdate(hash);
        discoveredWaystones.add(hash);
        if (sync) {
            syncData();
        }
    }

    @Override
    public boolean hasDiscoveredWaystone(WaystoneBlockEntity waystone) {
        return discoveredWaystones.contains(waystone.getHash());
    }

    @Override
    public void forgetWaystone(WaystoneBlockEntity waystone) {
        forgetWaystone(waystone.getHash());
    }

    @Override
    public void forgetWaystone(String hash) {
        forgetWaystone(hash, true);
    }

    @Override
    public void forgetWaystone(String hash, boolean sync) {
        var waystone = Waystones.WAYSTONE_STORAGE.getWaystoneEntity(hash);
        var player = _this();
        if (waystone != null) {
            if (waystone.isGlobal()) {
                return;
            }
            var server = player.getServer();
            if ((server != null && !server.isDedicated()) || player.getUuid().equals(waystone.getOwner())) {
                waystone.setOwner(null);
            }
        }
        WaystoneEvents.REMOVE_WAYSTONE_EVENT.invoker().onRemove(hash);
        discoveredWaystones.remove(hash);
        if (sync) {
            syncData();
        }
    }

    @Override
    public void syncData() {
        if (!(_this() instanceof ServerPlayerEntity serverPlayerEntity)) {
            return;
        }
        PacketByteBuf packet = PacketByteBufs.create();
        packet.writeNbt(toTagW(new NbtCompound()));
        ServerPlayNetworking.send(serverPlayerEntity, WaystonePacketHandler.SYNC_PLAYER, packet);
    }

    @Override
    public Set<String> getDiscoveredWaystones() {
        return discoveredWaystones;
    }

    @Override
    public int getDiscoveredCount() {
        return discoveredWaystones.size();
    }

    @Override
    public ArrayList<String> getWaystonesSorted() {
        ArrayList<String> waystones = new ArrayList<>();
        HashSet<String> toRemove = new HashSet<>();
        for (String hash : discoveredWaystones) {
            if (Waystones.WAYSTONE_STORAGE.containsHash(hash)) {
                waystones.add(Waystones.WAYSTONE_STORAGE.getWaystoneEntity(hash).getWaystoneName());
            } else {
                toRemove.add(hash);
            }
        }
        for (String remove : toRemove) {
            discoveredWaystones.remove(remove);
        }

        waystones.sort(String::compareTo);
        return waystones;
    }

    @Override
    public ArrayList<String> getHashesSorted() {
        ArrayList<String> waystones = new ArrayList<>();
        HashSet<String> toRemove = new HashSet<>();
        for (String hash : discoveredWaystones) {
            if (Waystones.WAYSTONE_STORAGE.containsHash(hash)) {
                waystones.add(hash);
            } else {
                toRemove.add(hash);
            }
        }
        for (String remove : toRemove) {
            discoveredWaystones.remove(remove);
        }

        waystones.sort(Comparator.comparing(
            a -> Waystones.WAYSTONE_STORAGE.getWaystoneEntity(a).getWaystoneName()));
        return waystones;
    }


    @Inject(method = "writeCustomDataToNbt", at = @At("RETURN"))
    public void writeCustomDataToNbt(NbtCompound tag, CallbackInfo ci) {
        toTagW(tag);
    }

    @Override
    public NbtCompound toTagW(NbtCompound tag) {
        NbtCompound customTag = new NbtCompound();
        NbtList waystones = new NbtList();
        for (String waystone : discoveredWaystones) {
            waystones.add(NbtString.of(waystone));
        }
        customTag.put("discovered_waystones", waystones);
        customTag.putBoolean("view_discovered_waystones", this.viewDiscoveredWaystones);
        customTag.putBoolean("view_global_waystones", this.viewGlobalWaystones);

        tag.put("waystones", customTag);
        return tag;
    }

    @Override
    public void learnWaystones(PlayerEntity player, boolean overwrite) {
        discoveredWaystones.clear();
        this.discoveredWaystones.addAll(
            ((PlayerEntityMixinAccess) player).getDiscoveredWaystones());
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("RETURN"))
    public void readCustomDataFromNbt(NbtCompound tag, CallbackInfo ci) {
        fromTagW(tag);
    }

    @Override
    public void fromTagW(NbtCompound tag) {
        if (!tag.contains("waystones")) {
            return;
        }
        tag = tag.getCompound("waystones");
        if (tag.contains("discovered_waystones")) {
            discoveredWaystones.clear();
            HashSet<String> hashes = new HashSet<>();
            if (Waystones.WAYSTONE_STORAGE != null) {
                hashes = Waystones.WAYSTONE_STORAGE.getAllHashes();
            } else if (_this().world.isClient) {
                HashSet<String> tmpHashes = ClientStuff.getWaystoneHashes();
                if (tmpHashes != null) {
                    hashes = tmpHashes;
                }
            }
            NbtList waystones = tag.getList("discovered_waystones", NbtElement.STRING_TYPE);
            for (NbtElement waystone : waystones) {
                if (hashes.contains(waystone.asString())) {
                    discoveredWaystones.add(waystone.asString());
                    WaystoneEvents.DISCOVER_WAYSTONE_EVENT.invoker()
                        .onUpdate(waystone.asString());
                }
            }
        }
        if (tag.contains("view_global_waystones")) {
            this.viewGlobalWaystones = tag.getBoolean("view_global_waystones");
        }
        if (tag.contains("view_discovered_waystones")) {
            this.viewDiscoveredWaystones = tag.getBoolean("view_discovered_waystones");
        }
    }

    @Override
    public boolean shouldViewGlobalWaystones() {
        return this.viewGlobalWaystones;
    }

    @Override
    public boolean shouldViewDiscoveredWaystones() {
        return this.viewDiscoveredWaystones;
    }

    @Override
    public void toggleViewGlobalWaystones() {
        this.viewGlobalWaystones = !this.viewGlobalWaystones;
        syncData();
    }

    @Override
    public void toggleViewDiscoveredWaystones() {
        this.viewDiscoveredWaystones = !this.viewDiscoveredWaystones;
        syncData();
    }

    @Override
    public boolean hasDiscoveredWaystone(String hash) {
        return this.discoveredWaystones.contains(hash);
    }

    @Override
    public void discoverWaystones(HashSet<String> toLearn) {
        if (Waystones.WAYSTONE_STORAGE == null) {
            return;
        }
        toLearn.forEach(hash -> discoverWaystone(hash, false));
        syncData();
    }

    @Override
    public void forgetWaystones(HashSet<String> toForget) {
        toForget.forEach(hash -> this.forgetWaystone(hash, false));
        syncData();
    }

    @Override
    public void forgetAllWaystones() {
        discoveredWaystones.forEach(hash -> forgetWaystone(hash, false));
        syncData();
    }

}
