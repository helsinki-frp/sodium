package sodium;

public class Behavior<A> {
	protected Event<A> event;
	A value;
	A valueUpdate;
	private Listener cleanup;

    Behavior(Event<A> event, A initValue)
    {
    	this.event = event;
    	this.value = initValue;
    	Transaction.run((Transaction trans1) -> {
    		this.cleanup = event.listen(Node.NULL, trans1, (Transaction trans2, A a) -> {
	    		if (this.valueUpdate == null) {
	    			trans2.last(() -> {
	    				this.value = this.valueUpdate;
	    				this.valueUpdate = null;
	    			});
	    		}
	    		this.valueUpdate = a;
	    	});
    	});
    }

    /**
     * @return The value including any updates that have happened in this transaction.
     */
    A newValue()
    {
    	return valueUpdate == null ? value :  valueUpdate;
    }

    public Event<A> changes()
    {
    	return event;
    }

    public Event<A> values()
    {
    	return new Event<A>() {
    		@Override
    		public Listener listen(Node target, Transaction trans, TransactionHandler<A> action) {
    			action.run(trans, value);  // Start with the initial value.
    		    return changes().listen(target, trans, action);
    		}
    	}/*.lastFiringOnly()*/;  // Needed in case of an initial value and an update
    	                     // in the same transaction.
    }

	public final <B> Behavior<B> map(Lambda1<A,B> f)
	{
		return changes().map(f).hold(f.evaluate(value));
	}
	
	public final <B,C> Behavior<C> lift(Lambda2<A,B,C> f, Behavior<B> b)
	{
		Lambda1<A, Lambda1<B,C>> ffa = (A aa) -> (B bb) -> f.evaluate(aa,bb);
		Behavior<Lambda1<B,C>> bf = map(ffa);
		return apply(bf, b);
	}

	public static final <A,B,C> Behavior<C> lift(Lambda2<A,B,C> f, Behavior<A> a, Behavior<B> b)
	{
		return a.lift(f, b);
	}

	public static <A,B> Behavior<B> apply(final Behavior<Lambda1<A,B>> bf, final Behavior<A> ba)
	{
		final EventSink<B> out = new EventSink();

        Handler<Transaction> h = new Handler<Transaction>() {
            boolean fired = false;			
            @Override
            public void run(Transaction trans1) {
                if (fired) 
                    return;
                
                fired = true;
                trans1.prioritized(out.node, (Transaction trans2) -> {
                   out.send(trans2, bf.newValue().evaluate(ba.newValue()));
                   fired = false;
                });
            }
        };
    
        Listener l1 = bf.changes().listen_(out.node, (Transaction trans1, Lambda1<A,B> f) -> {
            h.run(trans1);
        });
        Listener l2 = ba.changes().listen_(out.node, (Transaction trans1, A a) -> {
            h.run(trans1);
        });
        return out.addCleanup(l1).addCleanup(l2).hold(bf.value.evaluate(ba.value));
	}

	public static <A> Behavior<A> switchB(final Behavior<Behavior<A>> bba)
	{
	    A za = bba.value.value;
	    final EventSink<A> out = new EventSink<A>();
        TransactionHandler<Behavior<A>> h = new TransactionHandler<Behavior<A>>() {
            private Listener currentListener;
            @Override
            public void run(Transaction trans2, Behavior<A> ba) {
                // Note: If any switch takes place during a transaction, then the
                // values().listen will always cause a sample to be fetched from the
                // one we just switched to. The caller will be fetching our output
                // using values().listen, and values() throws away all firings except
                // for the last one. Therefore, anything from the old input behaviour
                // that might have happened during this transaction will be suppressed.
                if (currentListener != null)
                    currentListener.unlisten();
                currentListener = ba.values().listen(out.node, trans2, (Transaction trans3, A a) -> {
                    out.send(trans3, a);
                });
            }

            @Override
            protected void finalize() throws Throwable {
                if (currentListener != null)
                    currentListener.unlisten();
            }
        };
        Listener l1 = bba.values().listen_(out.node, h);
        return out.addCleanup(l1).hold(za);
	}

	@Override
	protected void finalize() throws Throwable {
		cleanup.unlisten();
	}
}
