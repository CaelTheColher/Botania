/*
 * This class is distributed as part of the Botania Mod.
 * Get the Source Code in github:
 * https://github.com/Vazkii/Botania
 *
 * Botania is Open Source and distributed under the
 * Botania License: http://botaniamod.net/license.php
 */
package vazkii.botania.common.block.subtile.generating;

import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.particle.ItemStackParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import vazkii.botania.api.subtile.RadiusDescriptor;
import vazkii.botania.api.subtile.TileEntityGeneratingFlower;
import vazkii.botania.common.block.ModSubtiles;
import vazkii.botania.mixin.AccessorItemEntity;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

public class SubTileGourmaryllis extends TileEntityGeneratingFlower {
	private static final String TAG_COOLDOWN = "cooldown";
	private static final String TAG_DIGESTING_MANA = "digestingMana";
	public static final String TAG_LAST_FOODS = "lastFoods";
	public static final String TAG_LAST_FOOD_COUNT = "lastFoodCount";
	public static final String TAG_STREAK_LENGTH = "streakLength";
	private static final int RANGE = 1;
	private static final double[] STREAK_MULTIPLIERS = { 0, 1, 1.3, 1.5, 1.6, 1.7, 1.75, 1.8 };

	private int cooldown = 0;
	private int digestingMana = 0;
	private List<ItemStack> lastFoods = new LinkedList<>();
	private int streakLength = -1;
	private int lastFoodCount = 0;

	public SubTileGourmaryllis() {
		super(ModSubtiles.GOURMARYLLIS);
	}

	private int getMaxStreak() {
		return STREAK_MULTIPLIERS.length - 1;
	}

	private double getMultiplierForStreak(int index) {
		// special-case repeated first foods
		if (index == 0) {
			return 1.0 / ++lastFoodCount;
		} else {
			lastFoodCount = 1;
			return STREAK_MULTIPLIERS[index];
		}
	}

	/**
	 * Processes a food, placing it in the appropriate place in the history.
	 * 
	 * @return the last time the food showed up in history.
	 */
	private int processFood(ItemStack food) {
		for (ListIterator<ItemStack> it = lastFoods.listIterator(); it.hasNext();) {
			int index = it.nextIndex();
			ItemStack streakFood = it.next();
			if (streakFood.isItemEqualIgnoreDamage(food) && ItemStack.areTagsEqual(streakFood, food)) {
				it.remove();
				lastFoods.add(0, streakFood);
				return index;
			}
		}
		ItemStack newestFood = food.copy();
		newestFood.setCount(1);
		lastFoods.add(0, newestFood);
		if (lastFoods.size() >= getMaxStreak()) {
			lastFoods.remove(lastFoods.size() - 1);
		}
		return getMaxStreak();
	}

	@Override
	public void tickFlower() {
		super.tickFlower();

		if (getWorld().isClient) {
			return;
		}

		if (cooldown > -1) {
			cooldown--;
		}
		if (digestingMana != 0) {
			int munchInterval = 2 + (2 * lastFoodCount);

			if (cooldown == 0) {
				addMana(digestingMana);
				digestingMana = 0;

				float burpPitch = (float) Math.pow(2.0, (streakLength == 0 ? -lastFoodCount : streakLength) / 12.0);
				getWorld().playSound(null, getEffectivePos(), SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.BLOCKS, 1, burpPitch);
				sync();
			} else if (cooldown % munchInterval == 0) {
				getWorld().playSound(null, getEffectivePos(), SoundEvents.ENTITY_GENERIC_EAT, SoundCategory.BLOCKS, 0.5f, 1);

				Vec3d offset = getWorld().getBlockState(getEffectivePos()).getModelOffset(getWorld(), getEffectivePos()).add(0.4, 0.6, 0.4);

				((ServerWorld) getWorld()).spawnParticles(new ItemStackParticleEffect(ParticleTypes.ITEM, lastFoods.get(0)), getEffectivePos().getX() + offset.x, getEffectivePos().getY() + offset.y, getEffectivePos().getZ() + offset.z, 10, 0.1D, 0.1D, 0.1D, 0.03D);
			}
		}

		int slowdown = getSlowdownFactor();

		List<ItemEntity> items = getWorld().getNonSpectatingEntities(ItemEntity.class, new Box(getEffectivePos().add(-RANGE, -RANGE, -RANGE), getEffectivePos().add(RANGE + 1, RANGE + 1, RANGE + 1)));

		for (ItemEntity item : items) {
			ItemStack stack = item.getStack();

			int age = ((AccessorItemEntity) item).getAge();
			if (!stack.isEmpty() && stack.getItem().isFood() && item.isAlive() && age >= slowdown) {
				if (cooldown <= 0) {
					streakLength = Math.min(streakLength + 1, processFood(stack));

					int val = Math.min(12, stack.getItem().getFoodComponent().getHunger());
					digestingMana = val * val * 70;
					digestingMana *= getMultiplierForStreak(streakLength);
					cooldown = val * 10;
					item.playSound(SoundEvents.ENTITY_GENERIC_EAT, 0.2F, 0.6F);
					sync();
					((ServerWorld) getWorld()).spawnParticles(new ItemStackParticleEffect(ParticleTypes.ITEM, stack), item.getX(), item.getY(), item.getZ(), 20, 0.1D, 0.1D, 0.1D, 0.05D);
				}

				item.remove();
			}
		}
	}

	@Override
	public void writeToPacketNBT(CompoundTag cmp) {
		super.writeToPacketNBT(cmp);
		cmp.putInt(TAG_COOLDOWN, cooldown);
		cmp.putInt(TAG_DIGESTING_MANA, digestingMana);
		ListTag foodList = new ListTag();
		for (ItemStack food : lastFoods) {
			foodList.add(food.toTag(new CompoundTag()));
		}
		cmp.put(TAG_LAST_FOODS, foodList);
		cmp.putInt(TAG_LAST_FOOD_COUNT, lastFoodCount);
		cmp.putInt(TAG_STREAK_LENGTH, streakLength);
	}

	@Override
	public void readFromPacketNBT(CompoundTag cmp) {
		super.readFromPacketNBT(cmp);
		cooldown = cmp.getInt(TAG_COOLDOWN);
		digestingMana = cmp.getInt(TAG_DIGESTING_MANA);
		lastFoods.clear();
		ListTag foodList = cmp.getList(TAG_LAST_FOODS, 10);
		for (int i = 0; i < foodList.size(); i++) {
			lastFoods.add(ItemStack.fromTag(foodList.getCompound(i)));
		}
		lastFoodCount = cmp.getInt(TAG_LAST_FOOD_COUNT);
		streakLength = cmp.getInt(TAG_STREAK_LENGTH);
	}

	@Override
	public RadiusDescriptor getRadius() {
		return new RadiusDescriptor.Square(getEffectivePos(), RANGE);
	}

	@Override
	public int getMaxMana() {
		return 9000;
	}

	@Override
	public int getColor() {
		return 0xD3D604;
	}

}
