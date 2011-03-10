package org.norm.query;

import org.norm.PersistingElement;

public class LimitConstraintBuilder<T extends PersistingElement> {
	private final ClassConstraintBuilder<T> constraintBuilder;
	private final int limit;
	
	public LimitConstraintBuilder(ClassConstraintBuilder<T> constraintBuilder,
			int limit) {
		this.constraintBuilder = constraintBuilder;
		this.limit = limit;
	}
	
	public ClassConstraintBuilder<T> elements() {
		this.constraintBuilder.setLimit(this.limit);
		return this.constraintBuilder;
	}
}