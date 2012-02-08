package com.twitter.mesos.scheduler;

import java.util.Collection;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.Iterables;

import com.twitter.mesos.gen.Attribute;
import com.twitter.mesos.gen.Constraint;
import com.twitter.mesos.gen.ScheduledTask;
import com.twitter.mesos.gen.TaskConstraint;
import com.twitter.mesos.scheduler.SchedulingFilter.Veto;
import com.twitter.mesos.scheduler.SchedulingFilterImpl.AttributeLoader;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.twitter.common.base.MorePreconditions.checkNotBlank;

/**
 * Filter that determines whether a task's constraints are satisfied.
 *
 * @author William Farner
 */
class ConstraintFilter implements Function<Constraint, Optional<Veto>> {

  private static final Logger LOG = Logger.getLogger(ConstraintFilter.class.getName());

  private final String jobKey;
  private final Supplier<Collection<ScheduledTask>> activeTasksSupplier;
  private final AttributeLoader attributeLoader;
  private final Iterable<Attribute> hostAttributes;

  @VisibleForTesting
  static Veto unsatisfiedValueVeto(String constraint) {
    return new Veto("Constraint not satisfied: " + constraint);
  }

  @VisibleForTesting
  static Veto missingLimitVeto(String constraint) {
    return new Veto("Limit constraint not present: " + constraint);
  }

  @VisibleForTesting
  static Veto unsatisfiedLimitVeto(String constraint) {
    return new Veto("Constraint not satisfied: " + constraint);
  }

  /**
   * Creates a new constraint filer for a given job.
   *
   * @param jobKey Key for the job.
   * @param activeTasksSupplier Supplier to fetch active tasks (if necessary).
   * @param attributeLoader Interface to fetch host attributes (if necessary).
   * @param hostAttributes The attributes of the host to test against.
   */
  ConstraintFilter(
      String jobKey,
      Supplier<Collection<ScheduledTask>> activeTasksSupplier,
      AttributeLoader attributeLoader,
      Iterable<Attribute> hostAttributes) {

    this.jobKey = checkNotBlank(jobKey);
    this.activeTasksSupplier = checkNotNull(activeTasksSupplier);
    this.attributeLoader = checkNotNull(attributeLoader);
    this.hostAttributes = checkNotNull(hostAttributes);
  }

  @Override
  public Optional<Veto> apply(Constraint constraint) {
    Predicate<Attribute> matchName = new NameFilter(constraint.getName());
    @Nullable Attribute attribute =
        Iterables.getOnlyElement(Iterables.filter(hostAttributes, matchName), null);

    TaskConstraint taskConstraint = constraint.getConstraint();
    switch (taskConstraint.getSetField()) {
      case VALUE:
        boolean matches =
            AttributeFilter.matches(Optional.fromNullable(attribute), taskConstraint.getValue());
        return matches
            ? Optional.<Veto>absent()
            : Optional.of(unsatisfiedValueVeto(constraint.getName()));

      case LIMIT:
        if (attribute == null) {
          return Optional.of(missingLimitVeto(constraint.getName()));
        }

        boolean satisfied = AttributeFilter.matches(
            attribute,
            jobKey,
            taskConstraint.getLimit().getLimit(),
            activeTasksSupplier.get(),
            attributeLoader);
        return satisfied
            ? Optional.<Veto>absent()
            : Optional.of(unsatisfiedLimitVeto(constraint.getName()));

      default:
        LOG.warning("Failed to recognize the constraint type: " + taskConstraint.getSetField());
        throw new SchedulerException("Failed to recognize the constraint type: "
            + taskConstraint.getSetField());
    }
  }

  // Finds all the attributes given by the name.
  private static class NameFilter implements Predicate<Attribute> {
    private final String attributeName;

    NameFilter(String attributeName) {
      this.attributeName = attributeName;
    }

    @Override public boolean apply(Attribute attribute) {
      return attributeName.equals(attribute.getName());
    }
  }
}