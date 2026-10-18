package ru.pets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PetsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // ===================== Типы питомцев =====================
    enum PetType {
        ENDER("ender", "&5Эндер-сфера", Material.ENDER_PEARL),
        SLIME("slime", "&aСлизь-сфера", Material.SLIME_BALL),
        FIRE("fire", "&6Огненная сфера", Material.FIRE_CHARGE),
        SNOW("snow", "&fСнежная сфера", Material.SNOWBALL),
        MAGMA("magma", "&cМагма-сфера", Material.MAGMA_CREAM),
        OCEAN("ocean", "&bМорская сфера", Material.HEART_OF_THE_SEA);

        final String id;
        final String name;
        final Material icon;

        PetType(String id, String name, Material icon) {
            this.id = id;
            this.name = name;
            this.icon = icon;
        }

        static PetType byId(String id) {
            for (PetType t : values()) if (t.id.equals(id)) return t;
            return null;
        }
    }

    // ===================== Данные игрока =====================
    static class PlayerData {
        final Map<String, Integer> levels = new HashMap<>();
        String active = null;
        double balance = 0; // используется, только если нет Vault
    }

    // ===================== Экономика =====================
    interface Eco {
        double balance(Player p);
        boolean take(Player p, double amount);
    }

    class InternalEco implements Eco {
        public double balance(Player p) { return data(p).balance; }
        public boolean take(Player p, double a) {
            PlayerData d = data(p);
            if (d.balance < a) return false;
            d.balance -= a;
            return true;
        }
    }

    /** Отдельный класс, чтобы Vault грузился только если он установлен. */
    static class VaultEco implements Eco {
        private final net.milkbowl.vault.economy.Economy eco;

        private VaultEco(net.milkbowl.vault.economy.Economy eco) { this.eco = eco; }

        static Eco create(JavaPlugin plugin) {
            RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp =
                    plugin.getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
            return rsp == null ? null : new VaultEco(rsp.getProvider());
        }

        public double balance(Player p) { return eco.getBalance(p); }
        public boolean take(Player p, double a) {
            return eco.has(p, a) && eco.withdrawPlayer(p, a).transactionSuccess();
        }
    }

    // ===================== Поля =====================
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final Map<UUID, ItemDisplay> displays = new HashMap<>();
    private File dataFile;
    private Eco eco;
    private BukkitTask followTask;
    private long tick = 0;

    private static final int[] PET_SLOTS = {10, 11, 12, 13, 14, 15};
    private static final int SLOT_STATS = 4;
    private static final int SLOT_UPGRADE = 22;
    private static final int SLOT_BALANCE = 18;
    private static final int SLOT_REMOVE = 26;

    static class PetsHolder implements InventoryHolder {
        private Inventory inv;
        public Inventory getInventory() { return inv; }
    }

    // ===================== Жизненный цикл =====================
    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        eco = null;
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            try {
                eco = VaultEco.create(this);
            } catch (Throwable ignored) { }
        }
        if (eco == null) {
            eco = new InternalEco();
            getLogger().warning("Vault/экономика не найдена — используется внутренний баланс (/pets give <ник> <сумма>).");
        } else {
            getLogger().info("Подключена экономика через Vault.");
        }

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("pets").setExecutor(this);
        getCommand("pets").setTabCompleter(this);

        followTask = Bukkit.getScheduler().runTaskTimer(this, this::updatePets, 1L, 2L);
    }

    @Override
    public void onDisable() {
        if (followTask != null) followTask.cancel();
        for (ItemDisplay d : displays.values()) if (d.isValid()) d.remove();
        displays.clear();
        saveData();
    }

    // ===================== Данные =====================
    private PlayerData data(Player p) {
        return players.computeIfAbsent(p.getUniqueId(), k -> new PlayerData());
    }

    private void loadData() {
        players.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = y.getConfigurationSection("players");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                PlayerData d = new PlayerData();
                ConfigurationSection s = root.getConfigurationSection(key);
                d.active = s.getString("active");
                d.balance = s.getDouble("balance", 0);
                ConfigurationSection lv = s.getConfigurationSection("levels");
                if (lv != null) for (String pid : lv.getKeys(false)) d.levels.put(pid, lv.getInt(pid, 1));
                players.put(id, d);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
            String base = "players." + e.getKey();
            PlayerData d = e.getValue();
            y.set(base + ".active", d.active);
            y.set(base + ".balance", d.balance);
            for (Map.Entry<String, Integer> l : d.levels.entrySet()) y.set(base + ".levels." + l.getKey(), l.getValue());
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Не удалось сохранить data.yml: " + ex.getMessage());
        }
    }

    /** При первом /pets выдаём все питомцы 1 уровня. */
    private void ensureStarter(Player p) {
        PlayerData d = data(p);
        for (PetType t : PetType.values()) d.levels.putIfAbsent(t.id, 1);
    }

    // ===================== Статы =====================
    private double damageBonus(int lvl) { return lvl * getConfig().getDouble("damage-per-level", 2.0); }
    private double dodgeChance(int lvl) {
        return Math.min(getConfig().getDouble("dodge-max", 40.0), lvl * getConfig().getDouble("dodge-per-level", 0.5));
    }
    private double defense(int lvl) {
        return Math.min(getConfig().getDouble("defense-max", 50.0), lvl * getConfig().getDouble("defense-per-level", 0.5));
    }
    private int activeLevel(Player p) {
        PlayerData d = data(p);
        if (d.active == null) return 0;
        return d.levels.getOrDefault(d.active, 0);
    }

    // ===================== Текст/предметы =====================
    private static Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.1f", v);
    }

    private ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(c(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(c(s));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private List<String> statLines(int lvl) {
        List<String> l = new ArrayList<>();
        l.add("&7Уровень: &e" + lvl);
        l.add("&c⚔ Урон: &f+" + fmt(damageBonus(lvl)) + "%");
        l.add("&b≈ Уклонение: &f" + fmt(dodgeChance(lvl)) + "%");
        l.add("&9🛡 Защита: &f-" + fmt(defense(lvl)) + "% урона");
        return l;
    }

    // ===================== Меню =====================
    private void openMenu(Player p) {
        PetsHolder holder = new PetsHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, c("&8Питомцы"));
        holder.inv = inv;
        PlayerData d = data(p);

        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);

        PetType[] types = PetType.values();
        for (int i = 0; i < types.length && i < PET_SLOTS.length; i++) {
            PetType t = types[i];
            int lvl = d.levels.getOrDefault(t.id, 1);
            boolean active = t.id.equals(d.active);
            List<String> lore = new ArrayList<>(statLines(lvl));
            lore.add("");
            lore.add(active ? "&a✔ Выбран" : "&eНажми, чтобы выбрать");
            ItemStack it = item(t.icon, t.name, lore);
            if (active) {
                ItemMeta m = it.getItemMeta();
                m.setEnchantmentGlintOverride(true);
                it.setItemMeta(m);
            }
            inv.setItem(PET_SLOTS[i], it);
        }

        PetType act = d.active == null ? null : PetType.byId(d.active);
        if (act != null) {
            int lvl = d.levels.getOrDefault(act.id, 1);
            inv.setItem(SLOT_STATS, item(act.icon, "&fАктивный: " + act.name, statLines(lvl)));

            int max = getConfig().getInt("max-level", 100);
            double cost = getConfig().getDouble("upgrade-cost", 10000);
            String cur = getConfig().getString("currency-name", "риликов");
            List<String> up = new ArrayList<>();
            if (lvl >= max) {
                up.add("&aМаксимальный уровень!");
            } else {
                up.add("&7Цена: &e" + fmt(cost) + " " + cur);
                up.add("&7Следующий уровень: &e" + (lvl + 1));
                up.add("");
                up.add("&eНажми, чтобы прокачать");
            }
            inv.setItem(SLOT_UPGRADE, item(Material.EXPERIENCE_BOTTLE, "&6Прокачать питомца", up));
            inv.setItem(SLOT_REMOVE, item(Material.BARRIER, "&cУбрать питомца", List.of("&7Питомец перестанет следовать за тобой")));
        } else {
            inv.setItem(SLOT_STATS, item(Material.PAPER, "&fПитомец не выбран", List.of("&7Выбери питомца ниже")));
            inv.setItem(SLOT_UPGRADE, item(Material.EXPERIENCE_BOTTLE, "&6Прокачать питомца", List.of("&cСначала выбери питомца")));
        }

        String cur = getConfig().getString("currency-name", "риликов");
        inv.setItem(SLOT_BALANCE, item(Material.GOLD_INGOT, "&eБаланс",
                List.of("&7" + cur + ": &f" + fmt(eco.balance(p)))));

        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof PetsHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;

        int slot = e.getSlot();
        PlayerData d = data(p);

        for (int i = 0; i < PET_SLOTS.length && i < PetType.values().length; i++) {
            if (PET_SLOTS[i] == slot) {
                PetType t = PetType.values()[i];
                d.active = t.id;
                respawnPet(p);
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
                saveData();
                openMenu(p);
                return;
            }
        }

        if (slot == SLOT_REMOVE && d.active != null) {
            d.active = null;
            removePet(p);
            saveData();
            openMenu(p);
            return;
        }

        if (slot == SLOT_UPGRADE) {
            if (d.active == null) {
                p.sendMessage(c("&cСначала выбери питомца."));
                return;
            }
            int lvl = d.levels.getOrDefault(d.active, 1);
            int max = getConfig().getInt("max-level", 100);
            if (lvl >= max) {
                p.sendMessage(c("&aТвой питомец уже максимального уровня."));
                return;
            }
            double cost = getConfig().getDouble("upgrade-cost", 10000);
            String cur = getConfig().getString("currency-name", "риликов");
            if (!eco.take(p, cost)) {
                p.sendMessage(c("&cНужно " + fmt(cost) + " " + cur + " для повышения уровня."));
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
                return;
            }
            d.levels.put(d.active, lvl + 1);
            respawnPet(p); // обновить имя над питомцем
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            p.sendMessage(c("&aПитомец прокачан до &e" + (lvl + 1) + " &aуровня!"));
            saveData();
            openMenu(p);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof PetsHolder) e.setCancelled(true);
    }

    // ===================== Команда =====================
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("pets.admin")) {
                sender.sendMessage(c("&cНет прав."));
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(c("&cИспользование: /pets give <ник> <сумма>"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(c("&cИгрок не найден."));
                return true;
            }
            try {
                double amount = Double.parseDouble(args[2]);
                data(target).balance += amount;
                saveData();
                sender.sendMessage(c("&aВыдано " + fmt(amount) + " на внутренний баланс (работает без Vault)."));
            } catch (NumberFormatException ex) {
                sender.sendMessage(c("&cСумма должна быть числом."));
            }
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }
        boolean first = !players.containsKey(p.getUniqueId()) || data(p).levels.isEmpty();
        ensureStarter(p);
        if (first) {
            p.sendMessage(c("&aТебе выдан питомец &e1 &aуровня! Выбери его в меню."));
            saveData();
        }
        openMenu(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1 && s.hasPermission("pets.admin")) return List.of("give");
        return List.of();
    }

    // ===================== Питомец-сфера =====================
    private void removePet(Player p) {
        ItemDisplay d = displays.remove(p.getUniqueId());
        if (d != null && d.isValid()) d.remove();
    }

    private void respawnPet(Player p) {
        removePet(p);
        spawnPet(p);
    }

    private void spawnPet(Player p) {
        PlayerData d = data(p);
        PetType t = d.active == null ? null : PetType.byId(d.active);
        if (t == null) return;
        int lvl = d.levels.getOrDefault(t.id, 1);
        Location loc = targetLocation(p);

        ItemDisplay disp = p.getWorld().spawn(loc, ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(t.icon));
            e.setBillboard(Display.Billboard.CENTER);
            e.setTransformation(new Transformation(
                    new Vector3f(), new AxisAngle4f(), new Vector3f(0.7f, 0.7f, 0.7f), new AxisAngle4f()));
            e.setPersistent(false);
            e.setTeleportDuration(3);
            e.customName(c(t.name + " &7[Ур. " + lvl + "]"));
            e.setCustomNameVisible(true);
            e.setViewRange(0.5f);
        });
        displays.put(p.getUniqueId(), disp);
    }

    private Location targetLocation(Player p) {
        Location l = p.getLocation();
        Vector dir = l.getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, 0, 1);
        dir.normalize();
        Vector right = new Vector(-dir.getZ(), 0, dir.getX());
        double bob = Math.sin(tick / 6.0) * 0.12;
        return l.clone().add(right.multiply(0.9)).add(dir.multiply(-0.6)).add(0, 1.4 + bob, 0);
    }

    private void updatePets() {
        tick += 2;
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = data(p);
            if (d.active == null) {
                if (displays.containsKey(p.getUniqueId())) removePet(p);
                continue;
            }
            ItemDisplay disp = displays.get(p.getUniqueId());
            if (disp == null || !disp.isValid() || !disp.getWorld().equals(p.getWorld())) {
                respawnPet(p);
                continue;
            }
            disp.teleport(targetLocation(p));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        removePet(e.getPlayer());
        saveData();
    }

    // ===================== Бой: урон / уклонение / защита =====================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        // Бонус к урону питомца у атакующего
        Player attacker = null;
        if (e.getDamager() instanceof Player pl) attacker = pl;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pl) attacker = pl;

        if (attacker != null) {
            int lvl = activeLevel(attacker);
            if (lvl > 0) e.setDamage(e.getDamage() * (1.0 + damageBonus(lvl) / 100.0));
        }

        // Уклонение и защита у жертвы
        if (e.getEntity() instanceof Player victim) {
            int lvl = activeLevel(victim);
            if (lvl > 0) {
                if (Math.random() * 100.0 < dodgeChance(lvl)) {
                    e.setCancelled(true);
                    victim.sendActionBar(c("&bУклонение!"));
                    victim.playSound(victim.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.6f, 1.6f);
                    return;
                }
                e.setDamage(e.getDamage() * (1.0 - defense(lvl) / 100.0));
            }
        }
    }
}
