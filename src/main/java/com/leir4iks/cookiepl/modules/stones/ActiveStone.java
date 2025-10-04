package com.leir4iks.cookiepl.modules.stones;

import com.leir4iks.cookiepl.CookiePl;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

public class ActiveStone {

    private final CookiePl plugin;
    private final StoneManager manager;
    private final Player thrower;
    private final ArmorStand stone;
    private Vector velocity;
    private int inactivityTicks = 0;

    private final Vector gravityVector;
    private final double bounceModifier;
    private final double damageMultiplier;
    private final int removeDelay;

    public ActiveStone(CookiePl plugin, StoneManager manager, Player thrower, ArmorStand stone, Vector velocity) {
        this.plugin = plugin;
        this.manager = manager;
        this.thrower = thrower;
        this.stone = stone;
        this.velocity = velocity;

        double gravity = plugin.getConfig().getDouble("modules.throwable-stones.physics.gravity", 0.04);
        this.gravityVector = new Vector(0, -gravity, 0);
        this.bounceModifier = plugin.getConfig().getDouble("modules.throwable-stones.physics.bounce-modifier", 0.35);
        this.damageMultiplier = plugin.getConfig().getDouble("modules.throwable-stones.physics.damage-per-block-speed", 1.0);
        this.removeDelay = plugin.getConfig().getInt("modules.throwable-stones.physics.remove-delay-ticks", 200);
    }

    public void tick() {
        if (!isValid()) {
            manager.removeStone(this.stone, false);
            return;
        }

        velocity.add(this.gravityVector);

        Location currentLocation = stone.getLocation();
        RayTraceResult result = stone.getWorld().rayTrace(currentLocation, velocity, velocity.length(), FluidCollisionMode.NEVER, true, 0.5, entity -> entity != stone && !stone.getPassengers().contains(entity) && (thrower == null || !entity.equals(thrower)));

        if (result != null) {
            handleCollision(result);
        } else {
            stone.teleport(currentLocation.add(velocity));
            damageEntities();
        }

        updateRotation();

        if (velocity.lengthSquared() < 0.01) {
            inactivityTicks++;
            if (inactivityTicks > this.removeDelay) {
                manager.removeStone(this.stone, true);
            }
        } else {
            inactivityTicks = 0;
        }
    }

    private void handleCollision(RayTraceResult result) {
        stone.teleport(result.getHitPosition().toLocation(stone.getWorld()));

        Vector normal = (result.getHitBlockFace() != null) ? result.getHitBlockFace().getDirection() : result.getHitPosition().subtract(stone.getLocation().toVector()).normalize();
        velocity.reflect(normal).multiply(this.bounceModifier);

        if (result.getHitBlock() != null) {
            Block hitBlock = result.getHitBlock();
            hitBlock.getWorld().spawnParticle(Particle.BLOCK_DUST, stone.getLocation(), 15, 0.2, 0.2, 0.2, hitBlock.getBlockData());
            playSoundForMaterial(hitBlock);
        }

        if (result.getHitEntity() != null) {
            damageEntity(result.getHitEntity());
        }
    }

    private void damageEntities() {
        for (Entity entity : stone.getWorld().getNearbyEntities(stone.getLocation(), 0.5, 0.5, 0.5)) {
            if (entity != thrower && entity instanceof LivingEntity && !stone.getPassengers().contains(entity)) {
                damageEntity(entity);
            }
        }
    }

    private void damageEntity(Entity entity) {
        if (entity instanceof LivingEntity) {
            double damage = velocity.length() * this.damageMultiplier;
            if (damage > 0.1) {
                ((LivingEntity) entity).damage(damage, thrower);
            }
        }
    }

    private void updateRotation() {
        EulerAngle currentPose = stone.getHeadPose();
        double rotX = ThreadLocalRandom.current().nextDouble(-0.1, 0.1) * velocity.length();
        double rotY = ThreadLocalRandom.current().nextDouble(-0.1, 0.1) * velocity.length();
        double rotZ = ThreadLocalRandom.current().nextDouble(-0.1, 0.1) * velocity.length();
        stone.setHeadPose(currentPose.add(rotX, rotY, rotZ));
    }

    private void playSoundForMaterial(Block block) {
        String materialName = block.getType().toString().toLowerCase();
        Sound sound = Sound.BLOCK_STONE_HIT;
        if (materialName.contains("wood") || materialName.contains("log")) sound = Sound.BLOCK_WOOD_HIT;
        else if (materialName.contains("glass")) sound = Sound.BLOCK_GLASS_BREAK;
        else if (materialName.contains("wool")) sound = Sound.BLOCK_WOOL_HIT;
        else if (materialName.contains("leaves")) sound = Sound.BLOCK_GRASS_HIT;
        else if (materialName.contains("sand") || materialName.contains("dirt") || materialName.contains("gravel")) sound = Sound.BLOCK_GRAVEL_HIT;

        block.getWorld().playSound(block.getLocation(), sound, 0.8f, 1.0f);
    }

    public boolean isValid() {
        return this.stone != null && !this.stone.isDead();
    }

    public ArmorStand getStoneEntity() {
        return this.stone;
    }
}