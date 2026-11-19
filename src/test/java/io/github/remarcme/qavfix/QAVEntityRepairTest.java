package io.github.remarcme.qavfix;

import java.lang.reflect.Proxy;
import java.util.*;
import org.bukkit.entity.Entity;

public final class QAVEntityRepairTest {
    public static final class Vehicle {
        private Entity driverseat;
        private final UUID id = UUID.randomUUID();
        private final List<Entity> models = new ArrayList<>();
        private final Map<Integer, Entity> seats = new HashMap<>();
        static int reads;
        Vehicle(Entity driver, Entity model, Entity passenger) {
            driverseat = driver; models.add(model); seats.put(0, passenger);
        }
        public UUID getVehicleUUID() { reads++; return id; }
        public List<Entity> getModelEntities() { return models; }
        public Map<Integer, Entity> getPassagers() { return seats; }
    }

    static Entity entity(UUID id) {
        return (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "toString" -> id.toString();
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
    static Entity entity() { return entity(UUID.randomUUID()); }
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        var access = new QAVEntityRepair.Access(Vehicle.class);
        Entity old = entity(), live = entity(old.getUniqueId()), other = entity();
        Vehicle combined = new Vehicle(old, old, old);
        check(access.rebind(combined, other) == 0, "Unrelated UUID changed");
        check(!access.owns(combined, other), "Unrelated entity accepted");
        check(access.rebind(combined, live) == 3, "Combined handles not repaired");
        check(combined.driverseat == live && combined.models.get(0) == live && combined.seats.get(0) == live,
            "Combined handles incorrect");
        check(access.owns(combined, live), "Repaired entity not recognized");
        check(access.rebind(combined, live) == 0, "Repair not idempotent");

        Entity driver = entity(), model = entity(), passenger = entity();
        Vehicle split = new Vehicle(driver, model, passenger);
        Entity newModel = entity(model.getUniqueId()), newSeat = entity(passenger.getUniqueId()), newDriver = entity(driver.getUniqueId());
        check(access.rebind(split, newModel) == 1 && split.driverseat == driver && split.seats.get(0) == passenger,
            "Model repair changed independent seats");
        check(access.rebind(split, newSeat) == 1 && split.driverseat == driver && split.models.get(0) == newModel,
            "Passenger repair changed other roles");
        check(access.rebind(split, newDriver) == 1 && split.driverseat == newDriver, "Driver repair failed");

        for (String prefix : new String[]{"QA-Model=", "QA-Passager=", "(QAV)"})
            check(old.getUniqueId().equals(QAVEntityRepair.vehicleName(prefix + old.getUniqueId())), "Valid name rejected");
        for (String name : new String[]{"", "ordinary decor", "(QAV)1-1-1-1-1", "QA-Model=" + old.getUniqueId() + " trailing"})
            check(QAVEntityRepair.vehicleName(name) == null, "Invalid name accepted");
        check(QAVEntityRepair.vehicleName(null) == null, "Null name accepted");

        List<Vehicle> vehicles = new ArrayList<>();
        for (int i = 0; i < 500; i++) vehicles.add(new Vehicle(entity(), entity(), entity()));
        Vehicle.reads = 0;
        Map<UUID, Object> index = access.index(vehicles);
        int changed = 0;
        for (Vehicle vehicle : vehicles) {
            Object indexed = index.get(vehicle.id);
            changed += access.rebind(indexed, entity(vehicle.driverseat.getUniqueId()));
            changed += access.rebind(indexed, entity(vehicle.models.get(0).getUniqueId()));
            changed += access.rebind(indexed, entity(vehicle.seats.get(0).getUniqueId()));
        }
        check(Vehicle.reads == 500 && changed == 1500, "Batch performed excess registry lookups or missed repairs");
        UUID removedId = vehicles.remove(0).id;
        Vehicle added = new Vehicle(entity(), entity(), entity()); vehicles.add(added);
        check(access.find(vehicles, removedId) == null && access.find(vehicles, added.id) == added,
            "Interaction retained a removed vehicle");
        check(!access.index(vehicles).containsKey(removedId), "New batch retained stale registry");
        System.out.println("PASS: UUID safety, independent seats/model, idempotence, strict names, registry replacement.");
        System.out.println("PASS: 500 vehicles / 1500 carriers repaired with exactly 500 registry UUID reads per batch.");
    }
}
