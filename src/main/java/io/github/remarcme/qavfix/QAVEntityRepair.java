package io.github.remarcme.qavfix;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Entity;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Restores QAV references when Minecraft reloads an entity with the same UUID. */
public final class QAVEntityRepair extends JavaPlugin implements Listener {
    private static final Pattern NAME = Pattern.compile(
        "^(?:QA-Model=|QA-Passager=|\\(QAV\\))([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$");
    private Field vehiclesField;
    private Access access;
    private final Map<UUID, Entity> pending = new HashMap<>();
    private boolean scheduled, reportedError;
    private long repaired;

    @Override public void onEnable() {
        try {
            var qav = Bukkit.getPluginManager().getPlugin("QualityArmoryVehicles2");
            if (qav == null || !qav.getDescription().getVersion().equals("2.5.1"))
                throw new IllegalStateException("Supported QAV version: 2.5.1 only");
            var loader = qav.getClass().getClassLoader();
            vehiclesField = Class.forName("me.zombie_striker.qav.Main", false, loader).getField("vehicles");
            access = new Access(Class.forName("me.zombie_striker.qav.VehicleEntity", false, loader));
            Bukkit.getPluginManager().registerEvents(this, this);
            for (long tick : new long[]{1, 20, 100})
                Bukkit.getScheduler().runTaskLater(this, this::scan, tick);
            getLogger().info("Enabled for QAV 2.5.1. Use /qavrepair for repair totals.");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            getLogger().severe("Cannot initialize: " + ex);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    /** All reflection metadata is resolved once, outside entity loops. */
    static final class Access {
        final Field driver;
        final Method id, models, passengers;

        Access(Class<?> type) throws ReflectiveOperationException {
            driver = type.getDeclaredField("driverseat");
            if (driver.getType() != Entity.class) throw new IllegalStateException("Unexpected driver field");
            driver.setAccessible(true);
            id = type.getMethod("getVehicleUUID");
            models = type.getMethod("getModelEntities");
            passengers = type.getMethod("getPassagers");
        }

        Map<UUID, Object> index(Collection<?> vehicles) throws ReflectiveOperationException {
            Map<UUID, Object> result = new HashMap<>();
            for (Object vehicle : vehicles)
                if (vehicle != null) result.putIfAbsent((UUID) id.invoke(vehicle), vehicle);
            return result;
        }

        Object find(Collection<?> vehicles, UUID wanted) throws ReflectiveOperationException {
            for (Object vehicle : vehicles)
                if (vehicle != null && wanted.equals(id.invoke(vehicle))) return vehicle;
            return null;
        }

        @SuppressWarnings("unchecked")
        int rebind(Object vehicle, Entity live) throws ReflectiveOperationException {
            UUID entityId = live.getUniqueId();
            int changes = 0;
            Entity old = (Entity) driver.get(vehicle);
            if (old != null && old != live && entityId.equals(old.getUniqueId())) {
                driver.set(vehicle, live);
                changes++;
            }
            List<Entity> modelList = (List<Entity>) models.invoke(vehicle);
            for (int i = 0; i < modelList.size(); i++) {
                old = modelList.get(i);
                if (old != null && old != live && entityId.equals(old.getUniqueId())) {
                    modelList.set(i, live);
                    changes++;
                }
            }
            Map<Object, Entity> seats = (Map<Object, Entity>) passengers.invoke(vehicle);
            for (var entry : seats.entrySet()) {
                old = entry.getValue();
                if (old != null && old != live && entityId.equals(old.getUniqueId())) {
                    entry.setValue(live);
                    changes++;
                }
            }
            return changes;
        }

        boolean owns(Object vehicle, Entity live) throws ReflectiveOperationException {
            if (driver.get(vehicle) == live) return true;
            for (Object model : (List<?>) models.invoke(vehicle)) if (model == live) return true;
            for (Object seat : ((Map<?, ?>) passengers.invoke(vehicle)).values()) if (seat == live) return true;
            return false;
        }
    }

    static UUID vehicleName(String name) {
        if (name == null) return null;
        var match = NAME.matcher(name);
        return match.matches() ? UUID.fromString(match.group(1)) : null;
    }

    private UUID namedVehicle(Entity entity) {
        return switch (entity.getType()) {
            case ARMOR_STAND, TURTLE, CHICKEN, ENDERMITE -> vehicleName(entity.getCustomName());
            default -> null;
        };
    }

    private Collection<?> vehicles() throws IllegalAccessException {
        return (Collection<?>) vehiclesField.get(null);
    }

    private void error(Exception ex) {
        if (!reportedError) {
            reportedError = true;
            getLogger().log(java.util.logging.Level.SEVERE, "Entity repair failed; further errors suppressed until restart", ex);
        }
    }

    private void repairBatch(Collection<Entity> entities) {
        try {
            Map<UUID, Object> index = null;
            for (Entity entity : entities) {
                UUID id = namedVehicle(entity);
                if (id == null || !entity.isValid() || entity.isDead()) continue;
                if (index == null) index = access.index(vehicles());
                Object vehicle = index.get(id);
                if (vehicle != null) repaired += access.rebind(vehicle, entity);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) { error(ex); }
    }

    private void scan() {
        List<Entity> entities = new ArrayList<>();
        for (var world : Bukkit.getWorlds()) entities.addAll(world.getEntities());
        repairBatch(entities);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void loaded(EntitiesLoadEvent event) {
        repairBatch(event.getEntities());
        for (Entity entity : event.getEntities())
            if (namedVehicle(entity) != null) pending.put(entity.getUniqueId(), entity);
        if (pending.isEmpty() || scheduled) return;
        scheduled = true;
        Bukkit.getScheduler().runTask(this, () -> {
            scheduled = false;
            try { repairBatch(pending.values()); }
            finally { pending.clear(); }
        });
    }

    private void interaction(Entity entity, Cancellable event) {
        UUID id = namedVehicle(entity);
        if (id == null) return;
        try {
            // Read the current registry: never retain a vehicle after pickup/removal.
            Object vehicle = access.find(vehicles(), id);
            if (vehicle != null && entity.isValid() && !entity.isDead()) {
                repaired += access.rebind(vehicle, entity);
                if (access.owns(vehicle, entity)) return;
            }
            event.setCancelled(true);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            event.setCancelled(true);
            error(ex);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void interact(PlayerInteractEntityEvent event) { interaction(event.getRightClicked(), event); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void interactAt(PlayerInteractAtEntityEvent event) { interaction(event.getRightClicked(), event); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void manipulate(PlayerArmorStandManipulateEvent event) { interaction(event.getRightClicked(), event); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("qaventityrepair.admin")) return true;
        long before = repaired;
        scan();
        sender.sendMessage("QAV references repaired: " + (repaired - before) + " this scan, " + repaired + " since startup."
            + (reportedError ? " An error was logged; check the server log." : ""));
        return true;
    }

    @Override public void onDisable() { pending.clear(); }
}
