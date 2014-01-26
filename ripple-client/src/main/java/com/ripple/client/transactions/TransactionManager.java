package com.ripple.client.transactions;

import com.ripple.client.Client;
import com.ripple.client.enums.Command;
import com.ripple.client.pubsub.CallbackContext;
import com.ripple.client.pubsub.Publisher;
import com.ripple.client.requests.Request;
import com.ripple.client.responses.Response;
import com.ripple.client.subscriptions.AccountRoot;
import com.ripple.client.subscriptions.ServerInfo;
import com.ripple.core.enums.TransactionEngineResult;
import com.ripple.core.enums.TransactionType;
import com.ripple.core.coretypes.AccountID;
import com.ripple.core.coretypes.Amount;
import com.ripple.core.coretypes.hash.Hash256;
import com.ripple.core.coretypes.uint.UInt32;
import com.ripple.crypto.ecdsa.IKeyPair;

import java.util.*;

public class TransactionManager extends Publisher<TransactionManager.events> {
    public static abstract class events<T> extends Publisher.Callback<T> {}
    // This event is emitted with the Sequence of the AccountRoot
    public static abstract class OnValidatedSequence extends events<UInt32> {}

    Client client;
    AccountRoot accountRoot;
    AccountID accountID;
    IKeyPair keyPair;
    AccountTransactionsRequester txnRequester;

    private ArrayList<ManagedTxn> pending = new ArrayList<ManagedTxn>();

    public TransactionManager(Client client, final AccountRoot accountRoot, AccountID accountID, IKeyPair keyPair) {
        this.client = client;
        this.accountRoot = accountRoot;
        this.accountID = accountID;
        this.keyPair = keyPair;

        // We'd be subscribed yeah ;)
        this.client.on(Client.OnLedgerClosed.class, new Client.OnLedgerClosed() {
            @Override
            public void called(ServerInfo serverInfo) {
                checkAccountTransactions(serverInfo.ledger_index);

                if (!canSubmit() || getPending().isEmpty()) {
                    return;
                }
                ArrayList<ManagedTxn> sorted = sequenceSortedQueue();

                ManagedTxn first = sorted.get(0);
                Submission previous = first.lastSubmission();

                if (previous != null) {
                    long ledgersClosed = serverInfo.ledger_index - previous.ledgerSequence;
                    if (ledgersClosed > 5) {
                        resubmitWithSameSequence(first);
                    }
                }
            }
        });
    }


    Set<Long> seenValidatedSequences = new TreeSet<Long>();
    public long sequence = -1;

    private UInt32 locallyPreemptedSubmissionSequence() {
        long server = accountRoot.Sequence.longValue();
        if (sequence == -1 || server > sequence) {
            sequence = server;
        }
        return new UInt32(sequence++);
    }
    private boolean txnNotFinalizedAndSeenValidatedSequence(ManagedTxn txn) {
        return !txn.isFinalized() &&
               seenValidatedSequences.contains(txn.sequence().longValue());
    }


    public void queue(ManagedTxn tx) {
        queue(tx, locallyPreemptedSubmissionSequence());
    }

    // TODO: data structure that keeps txns in sequence sorted order
    public ArrayList<ManagedTxn> getPending() {
        return pending;
    }

    public ArrayList<ManagedTxn> sequenceSortedQueue() {
        ArrayList<ManagedTxn> queued = new ArrayList<ManagedTxn>(getPending());
        Collections.sort(queued, new Comparator<ManagedTxn>() {
            @Override
            public int compare(ManagedTxn lhs, ManagedTxn rhs) {
                int i = lhs.get(UInt32.Sequence).subtract(rhs.get(UInt32.Sequence)).intValue();
                return i > 0 ? 1 : i == 0 ? 0 : -1;
            }
        });
        return queued;
    }

    public int txnsPending() {
        return getPending().size();
    }

    // TODO, maybe this is an instance configurable strategy parameter
    public static long LEDGERS_BETWEEN_ACCOUNT_TX = 15;
    public static long ACCOUNT_TX_TIMEOUT = 5;
    private long lastTxnRequesterUpdate = 0;
    private long lastLedgerCheckedAccountTxns = 0;
    AccountTransactionsRequester.OnPage onTxnsPage = new AccountTransactionsRequester.OnPage() {
        @Override
        public void onPage(AccountTransactionsRequester.Page page) {
            lastTxnRequesterUpdate = client.serverInfo.ledger_index;

            if (page.hasNext()) {
                page.requestNext();
            } else {
                // We can only know if we got to here
                // TODO check what ledgerMax really means
                lastLedgerCheckedAccountTxns = Math.max(lastLedgerCheckedAccountTxns, page.ledgerMax());
                txnRequester = null;
            }
            // TODO: TODO: check this logic
            // We are assuming moving `forward` from a ledger_index_min or resume marker

            for (TransactionResult tr : page.transactionResults()) {
                notifyTransactionResult(tr);
            }
        }
    };

    private void checkAccountTransactions(int currentLedgerIndex) {
        if (pending.size() == 0) {
            lastLedgerCheckedAccountTxns = 0;
            return;
        }

        long ledgersPassed = currentLedgerIndex - lastLedgerCheckedAccountTxns;

        if ((lastLedgerCheckedAccountTxns == 0 || ledgersPassed >= LEDGERS_BETWEEN_ACCOUNT_TX)) {
            if (lastLedgerCheckedAccountTxns == 0) {
                lastLedgerCheckedAccountTxns = currentLedgerIndex;
                for (ManagedTxn txn : pending) {
                    for (Submission submission : txn.submissions) {
                        lastLedgerCheckedAccountTxns = Math.min(lastLedgerCheckedAccountTxns, submission.ledgerSequence);
                    }
                }
                return; // and wait for next ledger close
            }
            if (txnRequester != null) {
                if ((currentLedgerIndex - lastTxnRequesterUpdate) >= ACCOUNT_TX_TIMEOUT) {
                    txnRequester.abort(); // no more OnPage
                    txnRequester = null; // and wait for next ledger close
                }
                // else keep waiting ;)
            } else {
                lastTxnRequesterUpdate = currentLedgerIndex;
                txnRequester = new AccountTransactionsRequester(client,
                                                                accountID,
                        onTxnsPage,
                                                                /* for good measure */
                                                                lastLedgerCheckedAccountTxns - 5);

                // Very important VVVVV
                txnRequester.setForward(true);
                txnRequester.request();
            }
        }
    }

    public void queue(final ManagedTxn txn, final UInt32 sequence) {
        getPending().add(txn);
        makeSubmitRequest(txn, sequence);
    }

    private boolean canSubmit() {
        return client.connected  &&
               client.serverInfo.primed() &&
               client.serverInfo.load_factor < 768 &&
               accountRoot.primed();
    }

    private void makeSubmitRequest(final ManagedTxn txn, final UInt32 sequence) {
        if (canSubmit()) {
            doSubmitRequest(txn, sequence);
        }
        else {
            // If we have submitted again, before this gets to execute
            // we should just bail out early, and not submit again.
            final int n = txn.submissions.size();
            client.on(Client.OnStateChange.class, new CallbackContext() {
                @Override
                public boolean shouldExecute() {
                    return canSubmit() && !shouldRemove();
                }

                @Override
                public boolean shouldRemove() {
                    // The next state change should cause this to remove
                    return txn.isFinalized() || n != txn.submissions.size();
                }
            }, new Client.OnStateChange() {
                @Override
                public void called(Client client) {
                    doSubmitRequest(txn, sequence);
                }
            });
        }
    }

    private Request doSubmitRequest(final ManagedTxn txn, UInt32 sequence) {
        // Comput the fee for the current load_factor
        Amount fee = client.serverInfo.transactionFee(txn);
        // Inside prepare we check if Fee and Sequence are the same, and if so
        // we don't recreated tx_blob, or resign ;)
        txn.prepare(keyPair, fee, sequence);

        final Request req = client.newRequest(Command.submit);
        // tx_blob is a hex string, right o' the bat
        req.json("tx_blob", txn.tx_blob);

        req.once(Request.OnSuccess.class, new Request.OnSuccess() {
            @Override
            public void called(Response response) {
                handleSubmitSuccess(txn, response);
            }
        });

        req.once(Request.OnError.class, new Request.OnError() {
            @Override
            public void called(Response response) {
                handleSubmitError(txn, response);
            }
        });

        // Keep track of the submission, including the hash submitted
        // to the network, and the ledger_index at that point in time.
        txn.trackSubmitRequest(req, client.serverInfo);
        req.request();
        return req;
    }

    public void handleSubmitError(ManagedTxn txn, Response res) {
//        resubmitFirstTransactionWithTakenSequence(transaction.sequence());
        if (txn.finalizedOrResponseIsToPriorSubmission(res)) {
            return;
        }
        switch (res.rpcerr) {
            case noNetwork:
                resubmitWithSameSequence(txn);
                break;
            default:
                // TODO, what other cases should this retry?
                finalizeTxnAndRemoveFromQueue(txn);
                txn.publisher().emit(ManagedTxn.OnSubmitError.class, res);
                break;
        }
    }

    public void handleSubmitSuccess(final ManagedTxn txn, final Response res) {
        if (txn.finalizedOrResponseIsToPriorSubmission(res)) {
            return;
        }
        TransactionEngineResult ter = res.engineResult();
        final UInt32 submitSequence = res.getSubmitSequence();
        switch (ter) {
            case tesSUCCESS:
                txn.publisher().emit(ManagedTxn.OnSubmitSuccess.class, res);
                return;

            case tefPAST_SEQ:
                resubmitWithNewSequence(txn);
                break;
            case terPRE_SEQ:
                on(OnValidatedSequence.class, new OnValidatedSequence() {
                    @Override
                    public void called(UInt32 sequence) {
                        if (txn.finalizedOrResponseIsToPriorSubmission(res)) {
                            removeListener(OnValidatedSequence.class, this);
                        }
                        if (sequence.equals(submitSequence)) {
                            // resubmit:
                            resubmit(txn, submitSequence);
                        }
                    }
                });
                break;
            case telINSUF_FEE_P:
                resubmit(txn, submitSequence);
                break;
            case tefALREADY:
                // We only get this if we are submitting with the correct transactionID
                // Do nothing, the transaction has already been submitted
                break;
            default:
                // In which cases do we patch ?
                switch (ter.resultClass()) {
                    case tecCLAIMED:
                        // Sequence was consumed, do nothing
                        finalizeTxnAndRemoveFromQueue(txn);
                        txn.publisher().emit(ManagedTxn.OnSubmitFailure.class, res);
                        break;
                    // What about this craziness /??
                    case temMALFORMED:
                    case tefFAILURE:
                    case telLOCAL_ERROR:
                    case terRETRY:
                        finalizeTxnAndRemoveFromQueue(txn);
                        if (getPending().isEmpty()) {
                            sequence--;
                        } else {
                            // Plug a Sequence gap and pre-emptively resubmit some
                            // rather than waiting for `OnValidatedSequence` which will take
                            // quite some ledgers
                            queueSequencePlugTxn(submitSequence);
                            resubmitGreaterThan(submitSequence);
                        }
                        txn.publisher().emit(ManagedTxn.OnSubmitFailure.class, res);
                        // look for
//                        pending.add(transaction);
                        // This is kind of nasty ..
                        break;

                }
                // TODO: Disposition not final ??!?!? ... ????
//                if (tr.resultClass() == TransactionEngineResult.Class.telLOCAL_ERROR) {
//                    pending.add(transaction);
//                }
                break;
        }
    }

    private void resubmitGreaterThan(UInt32 submitSequence) {
        for (ManagedTxn txn : getPending()) {
            if (txn.sequence().compareTo(submitSequence) == 1) {
                resubmitWithSameSequence(txn);
            }
        }
    }

    private void queueSequencePlugTxn(UInt32 sequence) {
        ManagedTxn plug = transaction(TransactionType.AccountSet);
        plug.setSequencePlug(true);
        queue(plug, sequence);
    }

    public void finalizeTxnAndRemoveFromQueue(ManagedTxn transaction) {
        transaction.setFinalized();
        getPending().remove(transaction);
    }

    private void resubmitFirstTransactionWithTakenSequence(UInt32 sequence) {
        for (ManagedTxn txn : getPending()) {
            if (txn.sequence().compareTo(sequence) == 0) {
                resubmitWithNewSequence(txn);
                break;
            }
        }
    }
    // We only EVER resubmit a txn with a new sequence if we actually seen it
    // this is the method that handles that,
    private void resubmitWithNewSequence(final ManagedTxn txn) {
        if (txn.isSequencePlug()) {
            // A sequence plug's sole purpose is to plug a sequence
            // The sequence has already been plugged.
            // So:
            return; // without further ado.
        }

        // ONLY ONLY ONLY if we've actually seen the Sequence
        if (txnNotFinalizedAndSeenValidatedSequence(txn)) {
            resubmit(txn, locallyPreemptedSubmissionSequence());
        } else {
            // requesting account_tx now and then (as we do) should take care of this
            on(OnValidatedSequence.class,
                new CallbackContext() {
                    @Override
                    public boolean shouldExecute() {
                        return !txn.isFinalized();
                    }

                    @Override
                    public boolean shouldRemove() {
                        return txn.isFinalized();
                    }
                },
                new OnValidatedSequence() {
                    @Override
                    public void called(UInt32 uInt32) {
                        // Again, just to be safe.
                        if (txnNotFinalizedAndSeenValidatedSequence(txn)) {
                            resubmit(txn, locallyPreemptedSubmissionSequence());
                        }
                    }
            });
        }
    }

    private void resubmit(ManagedTxn txn, UInt32 sequence) {
        makeSubmitRequest(txn, sequence);
    }

    private void resubmitWithSameSequence(ManagedTxn txn) {
        UInt32 previouslySubmitted = txn.sequence();
        makeSubmitRequest(txn, previouslySubmitted);
    }

    public ManagedTxn payment() {
        return transaction(TransactionType.Payment);
    }

    private ManagedTxn transaction(TransactionType tt) {
        ManagedTxn txn = new ManagedTxn(tt);
        txn.put(AccountID.Account, accountID);
        return txn;
    }

    public void notifyTransactionResult(TransactionResult tr) {
        if (!tr.validated) {
            return;
        }
        UInt32 txnSequence = tr.transaction.get(UInt32.Sequence);
        seenValidatedSequences.add(txnSequence.longValue());

        ManagedTxn txn = submittedTransactionForHash(tr.hash);
        if (txn != null) {
            // TODO: icky
            tr.submittedTransaction = txn;
            finalizeTxnAndRemoveFromQueue(txn);
            txn.publisher().emit(ManagedTxn.OnTransactionValidated.class, tr);
        } else {
            // preempt the terPRE_SEQ
            resubmitFirstTransactionWithTakenSequence(txnSequence);
            emit(OnValidatedSequence.class, txnSequence.add(new UInt32(1)));
        }
    }

    private ManagedTxn submittedTransactionForHash(Hash256 hash) {
        for (ManagedTxn transaction : getPending()) {
            if (transaction.wasSubmittedWith(hash)) {
                return transaction;
            }
        }
        return null;
    }
}
