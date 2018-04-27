@file:JvmName("Utils")

package net.corda.core

import net.corda.core.concurrent.CordaFuture
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.internal.concurrent.openFuture
import net.corda.core.internal.concurrent.thenMatch
import net.corda.core.messaging.DataFeed
import net.corda.core.transactions.SignedTransaction
import rx.Observable
import rx.Observer

// TODO Delete this file once the Future stuff is out of here

fun <A> CordaFuture<out A>.toObservable(): Observable<A> {
    return Observable.create { subscriber ->
        thenMatch({
            subscriber.onNext(it)
            subscriber.onCompleted()
        }, {
            subscriber.onError(it)
        })
    }
}

/**
 * Returns a [CordaFuture] bound to the *first* item emitted by this Observable. The future will complete with a
 * NoSuchElementException if no items are emitted or any other error thrown by the Observable. If it's cancelled then
 * it will unsubscribe from the observable.
 */
fun <T> Observable<T>.toFuture(): CordaFuture<T> = openFuture<T>().also {
    val subscription = first().subscribe(object : Observer<T> {
        override fun onNext(value: T) {
            it.set(value)
        }

        override fun onError(e: Throwable) {
            it.setException(e)
        }

        override fun onCompleted() {}
    })
    it.then {
        if (it.isCancelled) {
            subscription.unsubscribe()
        }
    }
}

/**
 * Returns a [DataFeed] that transforms errors according to the provided [transform] function.
 */
fun <SNAPSHOT, ELEMENT> DataFeed<SNAPSHOT, ELEMENT>.mapErrors(transform: (Throwable) -> Throwable): DataFeed<SNAPSHOT, ELEMENT> {

    return copy(updates = updates.mapErrors(transform))
}

/**
 * Returns a [DataFeed] that processes errors according to the provided [action].
 */
fun <SNAPSHOT, ELEMENT> DataFeed<SNAPSHOT, ELEMENT>.doOnError(action: (Throwable) -> Unit): DataFeed<SNAPSHOT, ELEMENT> {

    return copy(updates = updates.doOnError(action))
}

/**
 * Returns an [Observable] that transforms errors according to the provided [transform] function.
 */
fun <ELEMENT> Observable<ELEMENT>.mapErrors(transform: (Throwable) -> Throwable): Observable<ELEMENT> {

    return onErrorResumeNext { error ->
        Observable.error(transform(error))
    }
}

class TopologicalSort {
    private val forwardGraph = HashMap<SecureHash, LinkedHashSet<SignedTransaction>>()
    private val transactions = ArrayList<SignedTransaction>()

    fun add(stx: SignedTransaction) {
        for (input in stx.inputs) {
            forwardGraph.getOrPut(input.txhash) { LinkedHashSet() }.add(stx)
        }
        transactions.add(stx)
    }

    fun finalise(): List<SignedTransaction> {
        val visited = HashSet<SecureHash>(transactions.size)
        val result = ArrayList<SignedTransaction>(transactions.size)

        fun visit(transaction: SignedTransaction) {
            if (transaction.id !in visited) {
                visited.add(transaction.id)
                forwardGraph[transaction.id]?.forEach(::visit)
                result.add(transaction)
            }
        }

        transactions.forEach(::visit)
        return result.reversed()
    }
}

fun getOutputStateRefs(stx: SignedTransaction): List<StateRef> {
    return stx.coreTransaction.outputs.mapIndexed { i, _ -> StateRef(stx.id, i) }
}

fun Observable<SignedTransaction>.topologicalSort(initialUnspentRefs: Collection<StateRef>): Observable<SignedTransaction> {
    data class State(
            val unspentRefs: HashSet<StateRef>,
            val bufferedTopologicalSort: TopologicalSort,
            val bufferedInputs: HashSet<StateRef>,
            val bufferedOutputs: HashSet<StateRef>
    )

    var state = State(
            unspentRefs = HashSet(initialUnspentRefs),
            bufferedTopologicalSort = TopologicalSort(),
            bufferedInputs = HashSet(),
            bufferedOutputs = HashSet()
    )

    return concatMapIterable { stx ->
        val results = ArrayList<SignedTransaction>()
        if (state.unspentRefs.containsAll(stx.inputs)) {
            // Dependencies are satisfied
            state.unspentRefs.removeAll(stx.inputs)
            state.unspentRefs.addAll(getOutputStateRefs(stx))
            results.add(stx)
        } else {
            // Dependencies are not satisfied, buffer
            state.bufferedTopologicalSort.add(stx)
            state.bufferedInputs.addAll(stx.inputs)
            for (outputRef in getOutputStateRefs(stx)) {
                if (!state.bufferedInputs.remove(outputRef)) {
                    state.bufferedOutputs.add(outputRef)
                }
            }
            for (inputRef in stx.inputs) {
                if (!state.bufferedOutputs.remove(inputRef)) {
                    state.bufferedInputs.add(inputRef)
                }
            }
        }
        if (state.unspentRefs.containsAll(state.bufferedInputs)) {
            // Buffer satisfied
            results.addAll(state.bufferedTopologicalSort.finalise())
            state.unspentRefs.removeAll(state.bufferedInputs)
            state.unspentRefs.addAll(state.bufferedOutputs)
            state = State(
                    unspentRefs = state.unspentRefs,
                    bufferedTopologicalSort = TopologicalSort(),
                    bufferedInputs = HashSet(),
                    bufferedOutputs = HashSet()
            )
            results
        } else {
            // Buffer not satisfied
            state = State(
                    unspentRefs = state.unspentRefs,
                    bufferedTopologicalSort = state.bufferedTopologicalSort,
                    bufferedInputs = state.bufferedInputs,
                    bufferedOutputs = state.bufferedOutputs
            )
            results
        }
    }
}
