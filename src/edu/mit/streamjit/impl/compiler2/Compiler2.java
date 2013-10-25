package edu.mit.streamjit.impl.compiler2;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.math.LongMath;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Primitives;
import edu.mit.streamjit.api.RoundrobinSplitter;
import edu.mit.streamjit.api.StreamCompilationFailedException;
import edu.mit.streamjit.api.Worker;
import edu.mit.streamjit.impl.blob.Blob;
import edu.mit.streamjit.impl.blob.Blob.Token;
import edu.mit.streamjit.impl.blob.DrainData;
import edu.mit.streamjit.impl.common.Configuration;
import edu.mit.streamjit.impl.common.Configuration.SwitchParameter;
import edu.mit.streamjit.impl.common.IOInfo;
import edu.mit.streamjit.impl.compiler.Schedule;
import edu.mit.streamjit.util.CollectionUtils;
import static edu.mit.streamjit.util.Combinators.*;
import edu.mit.streamjit.util.bytecode.Module;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

/**
 *
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 9/22/2013
 */
public class Compiler2 {
	private final ImmutableSet<ActorArchetype> archetypes;
	private final NavigableSet<Actor> actors;
	private ImmutableSortedSet<ActorGroup> groups;
	private final Configuration config;
	private final int maxNumCores;
	private final DrainData initialState;
	private final Set<Storage> storage;
	private ImmutableMap<ActorGroup, Integer> externalSchedule;
	private final Map<Token, MethodHandle> tokenInputIndices = new HashMap<>(), tokenOutputIndices = new HashMap<>();
	/**
	 * The Token "schedule": the number of items read or written per steady
	 * state.
	 */
	private final Map<Token, Integer> tokenItemsRead = new HashMap<>(), tokenItemsWritten = new HashMap<>();
	private final Module module = new Module();
	private ImmutableMap<ActorGroup, Integer> initSchedule;
	private ImmutableMap<Storage, ConcreteStorage> globalStorage;
	public Compiler2(Set<Worker<?, ?>> workers, Configuration config, int maxNumCores, DrainData initialState) {
		Map<Class<?>, ActorArchetype> archetypesBuilder = new HashMap<>();
		Map<Worker<?, ?>, Actor> actorsBuilder = new HashMap<>();
		for (Worker<?, ?> w : workers) {
			@SuppressWarnings("unchecked")
			Class<? extends Worker<?, ?>> wClass = (Class<? extends Worker<?, ?>>)w.getClass();
			if (archetypesBuilder.get(wClass) == null)
				archetypesBuilder.put(wClass, new ActorArchetype(wClass, module));
			Actor actor = new Actor(w, archetypesBuilder.get(wClass));
			actorsBuilder.put(w, actor);
		}
		this.archetypes = ImmutableSet.copyOf(archetypesBuilder.values());
		this.actors = new TreeSet<>(actorsBuilder.values());

		Table<Object, Object, Storage> storageTable = HashBasedTable.create();
		for (Actor a : actors)
			a.connect(actorsBuilder, storageTable);
		this.storage = new HashSet<>(storageTable.values());
		for (Object o : storageTable.rowKeySet())
			if (o instanceof Token)
				tokenInputIndices.put((Token)o, MethodHandles.identity(int.class));
		for (Object o : storageTable.columnKeySet())
			if (o instanceof Token)
				tokenOutputIndices.put((Token)o, MethodHandles.identity(int.class));

		this.config = config;
		this.maxNumCores = maxNumCores;
		this.initialState = initialState;
	}

	public Blob compile() {
		fuse();
		schedule();
//		identityRemoval();
//		splitterRemoval();
		//joinerRemoval();
		unbox();

		initSchedule();
		createStorage();
		return null;
	}

	/**
	 * Fuses actors into groups as directed by the configuration.
	 */
	private void fuse() {
		List<ActorGroup> actorGroups = new ArrayList<>();
		for (Actor a : actors)
			actorGroups.add(ActorGroup.of(a));

		//Fuse as much as possible.
		outer: do {
			for (Iterator<ActorGroup> it = actorGroups.iterator(); it.hasNext();) {
				ActorGroup g = it.next();
				String paramName = String.format("fuse%d", g.id());
				SwitchParameter<Boolean> fuseParam = config.getParameter(paramName, SwitchParameter.class, Boolean.class);
				//TODO: initial data prevents fusion
				if (g.isPeeking() || !fuseParam.getValue() || g.predecessorGroups().size() > 1)
					continue;
				ActorGroup fusedGroup = ActorGroup.fuse(g, g.predecessorGroups().iterator().next());
				it.remove();
				actorGroups.add(fusedGroup);
				continue outer;
			}
			break;
		} while (true);

		this.groups = ImmutableSortedSet.copyOf(groups);
	}

	/**
	 * Computes each group's internal schedule and the external schedule.
	 */
	private void schedule() {
		for (ActorGroup g : groups)
			internalSchedule(g);

		Schedule.Builder<ActorGroup> scheduleBuilder = Schedule.builder();
		for (ActorGroup g : groups) {
			for (Storage e : g.outputs()) {
				if (!e.hasDownstreamActor())
					continue;
				ActorGroup other = e.downstreamActor().group();
				int upstreamAdjust = g.schedule().get(e.upstreamActor());
				int downstreamAdjust = other.schedule().get(e.downstreamActor());
				scheduleBuilder.connect(g, other)
						.push(e.push() * upstreamAdjust)
						.pop(e.pop() * downstreamAdjust)
						.peek(e.peek() * downstreamAdjust)
						.bufferExactly(0);
			}
		}
		try {
			externalSchedule = scheduleBuilder.build().getSchedule();
		} catch (Schedule.ScheduleException ex) {
			throw new StreamCompilationFailedException("couldn't find external schedule", ex);
		}

		//Compute the Token "schedule".
		for (ActorGroup g : groups) {
			for (Storage s : g.inputs()) {
				if (s.hasUpstreamActor()) continue;
				int itemsReadFromToken = externalSchedule.get(g) * g.schedule().get(s.downstreamActor()) * s.pop();
				tokenItemsRead.put(s.upstreamToken(), itemsReadFromToken);
			}
			for (Storage s : g.outputs()) {
				if (s.hasDownstreamActor()) continue;
				int itemsWrittenToToken = externalSchedule.get(g) * g.schedule().get(s.upstreamActor()) * s.push();
				tokenItemsWritten.put(s.downstreamToken(), itemsWrittenToToken);
			}
		}
	}

	/**
	 * Computes the internal schedule for the given group.
	 */
	private void internalSchedule(ActorGroup g) {
		Schedule.Builder<Actor> scheduleBuilder = Schedule.builder();
		scheduleBuilder.addAll(g.actors());
		Map<Worker<?, ?>, Actor> map = new HashMap<>();
		for (Actor a : g.actors())
			map.put(a.worker(), a);
		for (IOInfo info : IOInfo.internalEdges(map.keySet())) {
			scheduleBuilder.connect(map.get(info.upstream()), map.get(info.downstream()))
					.push(info.upstream().getPushRates().get(info.getUpstreamChannelIndex()).max())
					.pop(info.downstream().getPopRates().get(info.getDownstreamChannelIndex()).max())
					.peek(info.downstream().getPeekRates().get(info.getDownstreamChannelIndex()).max())
					.bufferExactly(0);
		}

		try {
			Schedule<Actor> schedule = scheduleBuilder.build();
			g.setSchedule(schedule.getSchedule());
		} catch (Schedule.ScheduleException ex) {
			throw new StreamCompilationFailedException("couldn't find internal schedule for group "+g.id(), ex);
		}
	}

	private void splitterRemoval() {
		for (Actor splitter : ImmutableSortedSet.copyOf(actors)) {
			List<MethodHandle> transfers = splitterTransferFunctions(splitter);
			if (transfers == null) continue;
			Storage survivor = Iterables.getOnlyElement(splitter.inputs());
			MethodHandle Sin = Iterables.getOnlyElement(splitter.inputIndexFunctions());
			for (int i = 0; i < splitter.outputs().size(); ++i) {
				Storage victim = splitter.outputs().get(i);
				MethodHandle t = transfers.get(i);
				MethodHandle t2 = MethodHandles.filterReturnValue(t, Sin);
				for (Object o : victim.downstream())
					if (o instanceof Actor) {
						Actor q = (Actor)o;
						List<Storage> inputs = q.inputs();
						List<MethodHandle> inputIndices = q.inputIndexFunctions();
						for (int j = 0; j < inputs.size(); ++j)
							if (inputs.get(j).equals(victim)) {
								inputs.set(j, survivor);
								inputIndices.set(j, MethodHandles.filterReturnValue(t2, inputIndices.get(j)));
							}
					} else if (o instanceof Token) {
						Token q = (Token)o;
						tokenInputIndices.put(q, MethodHandles.filterReturnValue(t2, tokenInputIndices.get(q)));
					} else
						throw new AssertionError(o);
			}
			removeActor(splitter);
		}
	}

	/**
	 * Returns transfer functions for the given splitter, or null if the actor
	 * isn't a splitter or isn't one of the built-in splitters or for some other
	 * reason we can't make transfer functions.
	 *
	 * A splitter has one transfer function for each output that maps logical
	 * output indices to logical input indices (representing the splitter's
	 * distribution pattern).
	 * @param a an actor
	 * @return transfer functions, or null
	 */
	private List<MethodHandle> splitterTransferFunctions(Actor a) {
		if (a.worker() instanceof RoundrobinSplitter) {
			//Nx, Nx + 1, Nx + 2, ..., Nx+(N-1)
			int N = a.outputs().size();
			ImmutableList.Builder<MethodHandle> transfer = ImmutableList.builder();
			for (int n = 0; n < N; ++n)
				transfer.add(add(mul(MethodHandles.identity(int.class), N), n));
			return transfer.build();
		} else //TODO: WeightedRoundrobinSplitter, DuplicateSplitter
			return null;
	}

	/**
	 * Removes an Actor from this compiler's data structures.  The Actor should
	 * already have been unlinked from the graph (no incoming edges); this takes
	 * care of removing it from the actors set, its actor group (possibly
	 * removing the group if it's now empty), and the schedule.
	 * @param a the actor to remove
	 */
	private void removeActor(Actor a) {
		assert actors.contains(a) : a;
		actors.remove(a);
		ActorGroup g = a.group();
		g.remove(a);
		if (g.actors().isEmpty()) {
			groups = ImmutableSortedSet.copyOf(Sets.difference(groups, ImmutableSet.of(g)));
			externalSchedule = ImmutableMap.copyOf(Maps.difference(externalSchedule, ImmutableMap.of(g, 0)).entriesOnlyOnLeft());
		}
	}

//	/**
//	 * Removes Identity instances from the graph, unless doing so would make the
//	 * graph empty.
//	 */
//	private void identityRemoval() {
//		//TODO: remove from group, possibly removing the group if it becomes empty
//		for (Iterator<Actor> iter = actors.iterator(); iter.hasNext();) {
//			if (actors.size() == 1)
//				break;
//			Actor actor = iter.next();
//			if (!actor.archetype().workerClass().equals(Identity.class))
//				continue;
//
//			iter.remove();
//			assert actor.predecessors().size() == 1 && actor.successors().size() == 1;
//			Object upstream = actor.predecessors().get(0), downstream = actor.successors().get(0);
//			if (upstream instanceof Actor)
//				replace(((Actor)upstream).successors(), actor, downstream);
//			if (downstream instanceof Actor)
//				replace(((Actor)downstream).predecessors(), actor, upstream);
//			//No index function changes required for Identity actors.
//		}
//	}
//
//	private static int replace(List<Object> list, Object target, Object replacement) {
//		int replacements = 0;
//		for (int i = 0; i < list.size(); ++i)
//			if (Objects.equals(list.get(0), target)) {
//				list.set(i, replacement);
//				++replacements;
//			}
//		return replacements;
//	}

	/**
	 * Symbolically unboxes a Storage if its common type is a wrapper type and
	 * all the connected Actors support unboxing.
	 */
	private void unbox() {
		next_storage: for (Storage s : storage) {
			Class<?> commonType = s.commonType();
			if (!Primitives.isWrapperType(commonType)) continue;
			for (Object o : s.upstream())
				if (o instanceof Actor && !((Actor)o).archetype().canUnboxOutput())
					continue next_storage;
			for (Object o : s.downstream())
				if (o instanceof Actor && !((Actor)o).archetype().canUnboxInput())
					continue next_storage;
			s.setType(Primitives.unwrap(s.commonType()));
		}
	}

	/**
	 * Computes the initialization schedule, which is in terms of ActorGroup
	 * executions.
	 */
	private void initSchedule() {
		Map<Storage, Set<Integer>> requiredReadIndices = new HashMap<>();
		for (Storage s : storage) {
			s.computeRequirements(externalSchedule,
					CollectionUtils.union(tokenItemsRead, tokenItemsWritten),
					CollectionUtils.union(tokenInputIndices, tokenOutputIndices));
			requiredReadIndices.put(s, new HashSet<>(s.readIndices()));
		}
		//TODO: initial state will reduce the required read indices.
		//TODO: what if we have more state than required for reads (due to rate
		//lag)?  Will need to leave space for it.

		/**
		 * Actual init: iterations of each group necessary to fill the required
		 * read indices of the output Storage.
		 */
		Map<ActorGroup, Integer> actualInit = new HashMap<>();
		for (ActorGroup g : groups)
			//TODO: this is assuming we can stop as soon as an iteration doesn't
			//help.  Will this always be true?
			for (int i = 0; ; ++i) {
				boolean changed = false;
				for (Map.Entry<Storage, Set<Integer>> e : g.writes(i).entrySet())
					changed |= requiredReadIndices.get(e.getKey()).removeAll(e.getValue());
				if (!changed) {
					actualInit.put(g, i);
					break;
				}
			}

		/**
		 * Total init, which is actual init plus allowances for downstream's
		 * total init.  Computed bottom-up via reverse iteration on groups.
		 */
		Map<ActorGroup, Integer> totalInit = new HashMap<>();
		for (ActorGroup g : groups.descendingSet()) {
			if (g.successorGroups().isEmpty())
				totalInit.put(g, actualInit.get(g));
			long us = externalSchedule.get(g);
			List<Long> downstreamReqs = new ArrayList<>(g.successorGroups().size());
			for (ActorGroup s : g.successorGroups()) {
				//I think reverse iteration guarantees bottom-up?
				assert totalInit.containsKey(s) : g.id() + " requires " + s.id();
				//them * (us / them) = us; we round up.
				int st = totalInit.get(s);
				int them = externalSchedule.get(s);
				downstreamReqs.add(LongMath.divide(LongMath.checkedMultiply(st, us), them, RoundingMode.UP));
			}
			totalInit.put(g, Ints.checkedCast(Collections.max(downstreamReqs) + actualInit.get(g)));
		}

		this.initSchedule = ImmutableMap.copyOf(totalInit);

		/**
		 * TODO: Compute the memory requirement for the init schedule, and the
		 * resulting steady-state capacities.
		 */
	}

	private void createStorage() {
		//TODO: initialState needs to be used somewhere.
		ImmutableMap.Builder<Storage, ConcreteStorage> globalStorageBuilder = ImmutableMap.builder();
		for (Storage s : storage) {
			s.computeRequirements(externalSchedule,
					CollectionUtils.union(tokenItemsRead, tokenItemsWritten),
					CollectionUtils.union(tokenInputIndices, tokenOutputIndices));
			if (!s.isInternal())
				globalStorageBuilder.put(s, new CircularArrayConcreteStorage(s.type(), s.actualCapacity(), s.throughput(),
						new Object[0] /*TODO: new init strategy*/));
		}
		this.globalStorage = globalStorageBuilder.build();
	}
}
