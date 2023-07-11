/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2021-2023 Threema GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.threema.domain.protocol.csp.fs;

import com.neilalexander.jnacl.NaCl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.base.ThreemaException;
import ch.threema.domain.fs.DHSession;
import ch.threema.domain.fs.DHSessionId;
import ch.threema.domain.fs.KDFRatchet;
import ch.threema.domain.models.Contact;
import ch.threema.domain.models.MessageId;
import ch.threema.domain.protocol.csp.coders.MessageCoder;
import ch.threema.domain.protocol.csp.connection.MessageQueue;
import ch.threema.domain.protocol.csp.messages.AbstractMessage;
import ch.threema.domain.protocol.csp.messages.BadMessageException;
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityData;
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityDataAccept;
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityDataInit;
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityDataMessage;
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityDataReject;
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityDataTerminate;
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityEnvelopeMessage;
import ch.threema.domain.protocol.csp.messages.fs.ForwardSecurityMode;
import ch.threema.domain.stores.ContactStore;
import ch.threema.domain.stores.DHSessionStoreException;
import ch.threema.domain.stores.DHSessionStoreInterface;
import ch.threema.domain.stores.IdentityStoreInterface;
import ch.threema.protobuf.csp.e2e.fs.Encapsulated;
import ch.threema.protobuf.csp.e2e.fs.Reject;
import ch.threema.protobuf.csp.e2e.fs.Terminate;
import ch.threema.protobuf.csp.e2e.fs.Version;

public class ForwardSecurityMessageProcessor {

	private static final @NonNull Logger logger = LoggerFactory.getLogger(ForwardSecurityMessageProcessor.class);

	private final @NonNull DHSessionStoreInterface dhSessionStoreInterface;
	private final @NonNull ContactStore contactStore;
	private final @NonNull IdentityStoreInterface identityStoreInterface;
	private final @NonNull MessageQueue messageQueue;
	private final @NonNull ForwardSecurityFailureListener failureListener;

	private interface ForwardSecurityStatusWrapper extends ForwardSecurityStatusListener {
		void setStatusListener(ForwardSecurityStatusListener forwardSecurityStatusListener);
	}

	private final @NonNull ForwardSecurityStatusWrapper statusListener = new ForwardSecurityStatusWrapper() {
		private @Nullable ForwardSecurityStatusListener listener;

		@Override
		public void setStatusListener(ForwardSecurityStatusListener forwardSecurityStatusListener) {
			this.listener = forwardSecurityStatusListener;
		}

		@Override
		public void newSessionInitiated(@NonNull DHSession session, @NonNull Contact contact) {
			if (listener != null) {
				listener.newSessionInitiated(session, contact);
			}
		}

		@Override
		public void responderSessionEstablished(@NonNull DHSession session, @NonNull Contact contact, boolean existingSessionPreempted) {
			if (listener != null) {
				listener.responderSessionEstablished(session, contact, existingSessionPreempted);
			}
		}

		@Override
		public void initiatorSessionEstablished(@NonNull DHSession session, @NonNull Contact contact) {
			if (listener != null) {
				listener.initiatorSessionEstablished(session, contact);
			}
		}

		@Override
		public void rejectReceived(@NonNull ForwardSecurityDataReject rejectData, @NonNull Contact contact, boolean sessionUnknown) {
			if (listener != null) {
				listener.rejectReceived(rejectData, contact, sessionUnknown);
			}
		}

		@Override
		public void sessionNotFound(@NonNull DHSessionId sessionId, @NonNull Contact contact) {
			if (listener != null) {
				listener.sessionNotFound(sessionId, contact);
			}
		}

		@Override
		public void sessionForMessageNotFound(@NonNull DHSessionId sessionId, @Nullable MessageId messageId, @NonNull Contact contact) {
			if (listener != null) {
				listener.sessionForMessageNotFound(sessionId, messageId, contact);
			}
		}

		@Override
		public void sessionBadState(@NonNull DHSessionId sessionId, @NonNull Contact contact) {
			if (listener != null) {
				listener.sessionBadState(sessionId, contact);
			}
		}

		@Override
		public void sessionTerminated(@NonNull DHSessionId sessionId, @NonNull Contact contact) {
			if (listener != null) {
				listener.sessionTerminated(sessionId, contact);
			}
		}

		@Override
		public void messagesSkipped(@NonNull DHSessionId sessionId, @NonNull Contact contact, int numSkipped) {
			if (listener != null) {
				listener.messagesSkipped(sessionId, contact, numSkipped);
			}
		}

		@Override
		public void messageOutOfOrder(@NonNull DHSessionId sessionId, @NonNull Contact contact, @Nullable MessageId messageId) {
			if (listener != null) {
				listener.messageOutOfOrder(sessionId, contact, messageId);
			}
		}

		@Override
		public void messageDecryptionFailed(@NonNull DHSessionId sessionId, @NonNull Contact contact, @Nullable MessageId failedMessageId) {
			if (listener != null) {
				listener.messageDecryptionFailed(sessionId, contact, failedMessageId);
			}
		}

		@Override
		public void first4DhMessageReceived(@NonNull DHSession session, @NonNull Contact contact) {
			if (listener != null) {
				listener.first4DhMessageReceived(session, contact);
			}
		}

		@Override
		public void unexpectedAppliedVersion(@NonNull DHSession session, int appliedVersion, @NonNull Contact contact) {
			if (listener != null) {
				listener.unexpectedAppliedVersion(session, appliedVersion, contact);
			}
		}

		@Override
		public void negotiatedVersionUpdated(@NonNull DHSession session, @NonNull Version updatedNegotiatedVersion, @NonNull Contact contact) {
			if (listener != null) {
				listener.negotiatedVersionUpdated(session, updatedNegotiatedVersion, contact);
			}
		}

		@Override
		public void messageWithoutFSReceived(@NonNull Contact contact, @NonNull DHSession session, @NonNull AbstractMessage message) {
			if (listener != null) {
				listener.messageWithoutFSReceived(contact, session, message);
			}
		}

		@Override
		public boolean hasForwardSecuritySupport(@NonNull Contact contact) {
			if (listener != null) {
				return listener.hasForwardSecuritySupport(contact);
			}
			// In case the listener is not set, which should never happen, we return true as this
			// is more likely. In case of a false positive, a session might not have been deleted,
			// which may result in another unsuccessful attempt to initiate a session.
			return true;
		}

		@Override
		public void updateFeatureMask(@NonNull Contact contact) {
			if (listener != null) {
				listener.updateFeatureMask(contact);
			}
		}
	};

	public ForwardSecurityMessageProcessor(
		@NonNull DHSessionStoreInterface dhSessionStoreInterface,
		@NonNull ContactStore contactStore,
		@NonNull IdentityStoreInterface identityStoreInterface,
		@NonNull MessageQueue messageQueue,
		@NonNull ForwardSecurityFailureListener failureListener
	) {
		this.dhSessionStoreInterface = dhSessionStoreInterface;
		this.contactStore = contactStore;
		this.identityStoreInterface = identityStoreInterface;
		this.messageQueue = messageQueue;
		this.failureListener = failureListener;
	}

	/**
	 * Process a forward security envelope message by attempting to decapsulate/decrypt it.
	 *
	 * @param sender Sender contact
	 * @param envelopeMessage The envelope with the encapsulated message
	 *
	 * @return Decapsulated message, if any, or null in case of a control message that has been consumed and does not need further processing
	 */
	public synchronized @Nullable AbstractMessage processEnvelopeMessage(
		@NonNull Contact sender,
		@NonNull ForwardSecurityEnvelopeMessage envelopeMessage
	) throws ThreemaException, BadMessageException {
		ForwardSecurityData data = envelopeMessage.getData();

		if (data instanceof ForwardSecurityDataInit) {
			processInit(sender, (ForwardSecurityDataInit) data);
		} else if (data instanceof ForwardSecurityDataAccept) {
			processAccept(sender, (ForwardSecurityDataAccept) data);
		} else if (data instanceof ForwardSecurityDataReject) {
			processReject(sender, (ForwardSecurityDataReject) data);
		} else if (data instanceof ForwardSecurityDataTerminate) {
			processTerminate(sender, (ForwardSecurityDataTerminate) data);
		} else if (data instanceof ForwardSecurityDataMessage) {
			return processMessage(sender, envelopeMessage);
		} else {
			// Unreachable if variant handling is in alignment with ForwardSecurityData.fromProtobuf
			throw new UnknownMessageTypeException("Unsupported message type");
		}

		return null;
	}

	public synchronized @NonNull ForwardSecurityEnvelopeMessage makeMessage(
		@NonNull Contact contact,
		@NonNull AbstractMessage innerMessage
	) throws ThreemaException, MessageTypeNotSupportedInSession {
		// Check if we already have a session with this contact
		DHSession session = dhSessionStoreInterface.getBestDHSession(identityStoreInterface.getIdentity(), contact.getIdentity());
		if (session == null) {
			// Establish a new DH session
			session = new DHSession(contact, identityStoreInterface);
			dhSessionStoreInterface.storeDHSession(session);
			logger.debug("Starting new DH session ID {} with {}", session.getId(), contact.getIdentity());
			statusListener.newSessionInitiated(session, contact);

			// Send init message
			ForwardSecurityDataInit init = new ForwardSecurityDataInit(
				session.getId(),
				DHSession.SUPPORTED_VERSION_RANGE,
				session.getMyEphemeralPublicKey()
			);
			sendMessageToContact(contact, init);

			// Check that the message type is supported in the new session
			Version requiredVersion = innerMessage.getMinimumRequiredForwardSecurityVersion();
			if (requiredVersion == null || requiredVersion.getNumber() > DHSession.SUPPORTED_VERSION_MIN.getNumber()) {
				throw new MessageTypeNotSupportedInSession("Message does not support initial DH session version", DHSession.SUPPORTED_VERSION_MIN);
			}
		}

		// Check that the message type is supported in the current session
		Version requiredVersion = innerMessage.getMinimumRequiredForwardSecurityVersion();
		if (requiredVersion == null || requiredVersion.getNumber() > session.getNegotiatedVersion().getNumber()) {
			throw new MessageTypeNotSupportedInSession("Message type is not supported in this session", session.getNegotiatedVersion());
		}

		// Obtain encryption key from ratchet
		KDFRatchet ratchet = session.getMyRatchet4DH();
		Encapsulated.DHType dhType = Encapsulated.DHType.FOURDH;
		ForwardSecurityMode forwardSecurityMode = ForwardSecurityMode.FOURDH;
		if (ratchet == null) {
			// 2DH mode
			ratchet = session.getMyRatchet2DH();
			dhType = Encapsulated.DHType.TWODH;
			forwardSecurityMode = ForwardSecurityMode.TWODH;
			if (ratchet == null) {
				throw new BadDHStateException("No DH mode negotiated");
			}
		}

		byte[] currentKey = ratchet.getCurrentEncryptionKey();
		long counter = ratchet.getCounter();
		ratchet.turn();

		// Save session, as ratchet has turned
		dhSessionStoreInterface.storeDHSession(session);

		// Symmetrically encrypt message (type byte + body)
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		bos.write(innerMessage.getType());
		try {
			bos.write(innerMessage.getBody());
		} catch (IOException e) {
			// Should never happen
			throw new RuntimeException(e);
		}
		byte[] plaintext = bos.toByteArray();
		// A new key is used for each message, so the nonce can be zero
		byte[] nonce = new byte[NaCl.NONCEBYTES];
		byte[] ciphertext = NaCl.symmetricEncryptData(plaintext, currentKey, nonce);

		ForwardSecurityDataMessage dataMessage = new ForwardSecurityDataMessage(session.getId(), dhType, counter, session.getAnnouncedVersion().getNumber(), ciphertext);
		ForwardSecurityEnvelopeMessage envelope = new ForwardSecurityEnvelopeMessage(dataMessage);

		// Copy attributes from inner message
		envelope.setFromIdentity(innerMessage.getFromIdentity());
		envelope.setToIdentity(innerMessage.getToIdentity());
		envelope.setMessageId(innerMessage.getMessageId());
		envelope.setDate(innerMessage.getDate());
		envelope.setMessageFlags(innerMessage.getMessageFlags() | innerMessage.getMessageTypeDefaultFlags());
		envelope.setPushFromName(innerMessage.getPushFromName());
		envelope.setForwardSecurityMode(forwardSecurityMode);
		envelope.setAllowSendingProfile(innerMessage.allowUserProfileDistribution());

		return envelope;
	}

	public void setStatusListener(@NonNull ForwardSecurityStatusListener listener) {
		this.statusListener.setStatusListener(listener);
	}

	public void warnIfMessageWithoutForwardSecurityReceived(@NonNull AbstractMessage message) {
		Contact contact = contactStore.getContactForIdentity(message.getFromIdentity());
		if (contact == null) {
			return;
		}
		DHSession bestSession;
		try {
			bestSession = dhSessionStoreInterface.getBestDHSession(identityStoreInterface.getIdentity(), message.getFromIdentity());
		} catch (DHSessionStoreException e) {
			logger.error("Could not get best session", e);
			return;
		}

		if (bestSession != null) {
			// The assumed version for incoming messages depends on the forward security state
			DHSession.State sessionState = bestSession.getState();
			Version assumedVersion;
			if (sessionState == DHSession.State.R20 || sessionState == DHSession.State.R24) {
				// In states R20 and R24, we still receive messages with 2DH and the min supported
				// version.
				assumedVersion = DHSession.SUPPORTED_VERSION_MIN;
			} else {
				// In the other states, we expect the incoming messages to be sent with the
				// negotiated version.
				assumedVersion = bestSession.getNegotiatedVersion();
			}

			Version minimumVersion = message.getMinimumRequiredForwardSecurityVersion();
			if (minimumVersion != null
				&& minimumVersion.getNumber() <= assumedVersion.getNumber()
			) {
				// TODO(ANDR-2452): Remove this feature mask update when enough clients have updated
				// Check whether this contact still supports forward security when receiving a
				// message without forward security.
				if (statusListener.hasForwardSecuritySupport(contact)) {
					statusListener.updateFeatureMask(contact);
				}

				// Warn only if the contact still has forward security support, otherwise a status
				// message that the contact has downgraded is shown to the user
				if (statusListener.hasForwardSecuritySupport(contact)) {
					statusListener.messageWithoutFSReceived(contact, bestSession, message);
				}
			}
		}
	}

	/**
	 * Clear all sessions with the peer contact and send a terminate message for each of those.
	 *
	 * @param contact the peer contact
	 */
	public void clearAndTerminateAllSessions(@NonNull Contact contact, @NonNull Terminate.Cause cause) {
		try {
			String myIdentity = identityStoreInterface.getIdentity();
			String peerIdentity = contact.getIdentity();
			DHSession session = dhSessionStoreInterface.getBestDHSession(myIdentity, peerIdentity);
			while (session != null) {
				// First delete session locally, then send terminate to contact
				dhSessionStoreInterface.deleteDHSession(myIdentity, peerIdentity, session.getId());
				sendMessageToContact(
					contact,
					new ForwardSecurityDataTerminate(session.getId(), cause)
				);
				session = dhSessionStoreInterface.getBestDHSession(myIdentity, peerIdentity);
			}
		} catch (DHSessionStoreException e) {
			logger.error("Could not delete DH sessions", e);
		} catch (ThreemaException e) {
			logger.error("Could not send DH session terminate", e);
		}
	}

	private void processInit(@NonNull Contact contact, @NonNull ForwardSecurityDataInit init) throws ThreemaException, BadMessageException {
		// Is there already a session with this ID?
		if (dhSessionStoreInterface.getDHSession(identityStoreInterface.getIdentity(), contact.getIdentity(), init.getSessionId()) != null) {
			// Silently discard init message for existing session
			return;
		}

		// The initiator will only send an Init if it does not have an existing session. This means
		// that any 4DH sessions that we have stored for this contact are obsolete and should be deleted.
		// We will keep 2DH sessions (which will have been initiated by us), as otherwise messages may
		// be lost during Init race conditions.
		boolean existingSessionPreempted = false;
		if (dhSessionStoreInterface.deleteAllSessionsExcept(identityStoreInterface.getIdentity(), contact.getIdentity(), init.getSessionId(), true) > 0) {
			existingSessionPreempted = true;
		}

		DHSession session = new DHSession(init.getSessionId(), init.getVersionRange(), init.getEphemeralPublicKey(), contact, identityStoreInterface);
		dhSessionStoreInterface.storeDHSession(session);
		logger.debug("Responding to new DH session ID {} request from {}", session.getId(), contact.getIdentity());
		statusListener.responderSessionEstablished(session, contact, existingSessionPreempted);

		// TODO(ANDR-2452): Remove this check when enough clients have updated
		if (!statusListener.hasForwardSecuritySupport(contact)) {
			statusListener.updateFeatureMask(contact);
		}

		if (statusListener.hasForwardSecuritySupport(contact)) {
			// Create and send accept in case the contact supports forward security
			ForwardSecurityDataAccept accept = new ForwardSecurityDataAccept(init.getSessionId(), DHSession.SUPPORTED_VERSION_RANGE, session.getMyEphemeralPublicKey());
			sendMessageToContact(contact, accept);
		} else {
			// If the contact does not have the feature mask set correctly, we terminate and clear
			// the session.
			clearAndTerminateAllSessions(contact, Terminate.Cause.DISABLED_BY_REMOTE);
		}
	}

	private void processAccept(@NonNull Contact contact, @NonNull ForwardSecurityDataAccept accept) throws ThreemaException, BadMessageException {
		DHSession session = dhSessionStoreInterface.getDHSession(identityStoreInterface.getIdentity(), contact.getIdentity(), accept.getSessionId());
		if (session == null) {
			// Session not found, probably lost local data or old accept
			logger.warn("No DH session found for accepted session ID {} from {}", accept.getSessionId(), contact.getIdentity());

			// Send "terminate" message for this session ID
			ForwardSecurityDataTerminate terminate = new ForwardSecurityDataTerminate(accept.getSessionId(), Terminate.Cause.UNKNOWN_SESSION);
			sendMessageToContact(contact, terminate);

			statusListener.sessionNotFound(accept.getSessionId(), contact);

			return;
		}

		session.processAccept(accept.getVersionRange(), accept.getEphemeralPublicKey(), contact, identityStoreInterface);
		dhSessionStoreInterface.storeDHSession(session);
		logger.info("Established 4DH session ID {} with {}, negotiated version: {}", session.getId(), contact.getIdentity(), session.getNegotiatedVersion());
		statusListener.initiatorSessionEstablished(session, contact);
	}

	private void processReject(@NonNull Contact contact, @NonNull ForwardSecurityDataReject reject) throws DHSessionStoreException {
		logger.warn("Received reject for DH session ID {} from {}, cause: {}", reject.getSessionId(), contact.getIdentity(), reject.getCause());
		DHSession session = dhSessionStoreInterface.getDHSession(identityStoreInterface.getIdentity(), contact.getIdentity(), reject.getSessionId());
		if (session != null) {
			// Discard session
			dhSessionStoreInterface.deleteDHSession(identityStoreInterface.getIdentity(), contact.getIdentity(), reject.getSessionId());
		} else {
			// Session not found, probably lost local data or old reject
			logger.info("No DH session found for rejected session ID {} from {}", reject.getSessionId(), contact.getIdentity());
		}

		statusListener.rejectReceived(reject, contact, session == null);

		// Refresh feature mask now, in case contact downgraded to a build without PFS
		statusListener.updateFeatureMask(contact);

		failureListener.notifyRejectReceived(contact, reject.getRejectedApiMessageId());
	}

	private @Nullable AbstractMessage processMessage(@NonNull Contact contact, @NonNull ForwardSecurityEnvelopeMessage envelopeMessage)
		throws ThreemaException, BadMessageException {

		ForwardSecurityDataMessage message = (ForwardSecurityDataMessage)envelopeMessage.getData();

		DHSession session = dhSessionStoreInterface.getDHSession(identityStoreInterface.getIdentity(), contact.getIdentity(), message.getSessionId());
		if (session == null) {
			// Session not found, probably lost local data or old message
			logger.warn("No DH session found for message {} in session ID {} from {}", envelopeMessage.getMessageId(), message.getSessionId(), contact.getIdentity());

			// Send reject message
			ForwardSecurityDataReject reject = new ForwardSecurityDataReject(message.getSessionId(), envelopeMessage.getMessageId(), Reject.Cause.UNKNOWN_SESSION);
			sendMessageToContact(contact, reject);

			statusListener.sessionForMessageNotFound(message.getSessionId(), envelopeMessage.getMessageId(), contact);

			return null;
		}

		// Validate the applied version
		final Version updatedNegotiatedVersion = session.validateAppliedVersion(message.getAppliedVersion());
		if (updatedNegotiatedVersion == null) {
			// Note that the applied version of a received message is set to V1_0 while decoding the
			// message when the applied version is unknown (e.g. newer than supported)
			logger.warn("Unexpected major version in applied version, negotiated={}, applied={}", session.getNegotiatedVersion(), message.getAppliedVersion());
			ForwardSecurityDataReject reject = new ForwardSecurityDataReject(message.getSessionId(), envelopeMessage.getMessageId(), Reject.Cause.STATE_MISMATCH);
			sendMessageToContact(contact, reject);
			statusListener.unexpectedAppliedVersion(session, message.getAppliedVersion(), contact);
			return null;
		}
		if (updatedNegotiatedVersion.getNumber() > session.getNegotiatedVersion().getNumber()) {
			statusListener.negotiatedVersionUpdated(session, updatedNegotiatedVersion, contact);
		}

		// Obtain appropriate ratchet and turn to match the message's counter value
		KDFRatchet ratchet = null;
		ForwardSecurityMode mode = ForwardSecurityMode.NONE;
		switch (message.getType()) {
			case TWODH:
				ratchet = session.getPeerRatchet2DH();
				mode = ForwardSecurityMode.TWODH;
				break;
			case FOURDH:
				ratchet = session.getPeerRatchet4DH();
				mode = ForwardSecurityMode.FOURDH;
				break;
		}

		if (ratchet == null) {
			// This can happen if the Accept message from our peer has been lost. In that case
			// they will think they are in 4DH mode, but we are still in 2DH.
			ForwardSecurityDataReject reject = new ForwardSecurityDataReject(message.getSessionId(), envelopeMessage.getMessageId(), Reject.Cause.STATE_MISMATCH);
			sendMessageToContact(contact, reject);
			statusListener.sessionBadState(message.getSessionId(), contact);
			return null;
		}

		// We should already be at the correct ratchet count since we increment it after
		// processing a message. If we have missed any messages, we will need to increment further.
		try {
			int numTurns = ratchet.turnUntil(message.getCounter());
			if (numTurns > 0) {
				statusListener.messagesSkipped(message.getSessionId(), contact, numTurns);
			}
		} catch (KDFRatchet.RatchetRotationException e) {
			statusListener.messageOutOfOrder(message.getSessionId(), contact, envelopeMessage.getMessageId());
			throw new BadMessageException("Out of order FS message, cannot decrypt");
		}

		// Symmetrically decrypt message
		byte[] ciphertext = message.getMessage();
		// A new key is used for each message, so the nonce can be zero
		byte[] nonce = new byte[NaCl.NONCEBYTES];
		byte[] plaintext = NaCl.symmetricDecryptData(ciphertext, ratchet.getCurrentEncryptionKey(), nonce);
		if (plaintext == null) {
			ForwardSecurityDataReject reject = new ForwardSecurityDataReject(message.getSessionId(), envelopeMessage.getMessageId(), Reject.Cause.STATE_MISMATCH);
			sendMessageToContact(contact, reject);
			statusListener.messageDecryptionFailed(message.getSessionId(), contact, envelopeMessage.getMessageId());
			return null;
		}

		logger.debug("Decrypted {} message ID {} from {} in session {} with applied version {}",
			mode,
			envelopeMessage.getMessageId(),
			contact.getIdentity(),
			session.toDebugString(),
			message.getAppliedVersion()
		);

		// Commit the updated negotiated version
		session.commitNegotiatedVersion(updatedNegotiatedVersion);

		// Turn the ratchet once, as we will not need the current encryption key anymore and the
		// next message from the peer must have a ratchet count of at least one higher
		ratchet.turn();

		if (mode == ForwardSecurityMode.FOURDH) {
			// If this was a 4DH message, then we should erase the 2DH peer ratchet, as we shall not
			// receive (or send) any further 2DH messages in this session. Note that this is also
			// necessary to determine the correct session state.
			if (session.getPeerRatchet2DH() != null) {
				session.discardPeerRatchet2DH();
			}

			// If this message was sent in what we also consider to be the "best" session (lowest ID),
			// then we can delete any other sessions.
			DHSession bestSession = dhSessionStoreInterface.getBestDHSession(identityStoreInterface.getIdentity(), contact.getIdentity());
			if (bestSession != null && bestSession.getId().equals(session.getId())) {
				dhSessionStoreInterface.deleteAllSessionsExcept(identityStoreInterface.getIdentity(), contact.getIdentity(), session.getId(), false);
			}

			// If this was the first 4DH message in this session, inform the user (only required in
			// version 1.0)
			if (ratchet.getCounter() == 2) {
				statusListener.first4DhMessageReceived(session, contact);
			}
		}

		// Save session, as ratchets and negotiated version may have changed
		dhSessionStoreInterface.storeDHSession(session);

		// Decode inner message and pass it to processor
		AbstractMessage innerMsg = new MessageCoder(contactStore, identityStoreInterface).decodeEncapsulated(plaintext, envelopeMessage, updatedNegotiatedVersion, contact);
		innerMsg.setForwardSecurityMode(mode);
		return innerMsg;
	}

	private void processTerminate(@NonNull Contact contact, @NonNull ForwardSecurityDataTerminate message) throws DHSessionStoreException {
		logger.debug("Terminating DH session ID {} with {}, cause: {}", message.getSessionId(), contact.getIdentity(), message.getCause());
		dhSessionStoreInterface.deleteDHSession(identityStoreInterface.getIdentity(), contact.getIdentity(), message.getSessionId());

		statusListener.sessionTerminated(message.getSessionId(), contact);

		// Refresh feature mask now, in case contact downgraded to a build without PFS
		statusListener.updateFeatureMask(contact);
	}

	private void sendMessageToContact(@NonNull Contact contact, @NonNull ForwardSecurityData data) throws ThreemaException {
		ForwardSecurityEnvelopeMessage message = new ForwardSecurityEnvelopeMessage(data);
		message.setToIdentity(contact.getIdentity());
		this.messageQueue.enqueue(message);
	}

	public static class UnknownMessageTypeException extends ThreemaException {
		public UnknownMessageTypeException(@NonNull String msg) {
			super(msg);
		}
	}

	public static class BadDHStateException extends ThreemaException {
		public BadDHStateException(@NonNull String msg) {
			super(msg);
		}
	}

	/**
	 * This exception is thrown, if a message can not be encapsulated because the given session does
	 * not support this message types.
	 */
	public static class MessageTypeNotSupportedInSession extends Exception {
		@NonNull
		private final Version negotiatedVersion;

		public MessageTypeNotSupportedInSession(@NonNull String msg, @NonNull Version negotiatedVersion) {
			super(msg);

			this.negotiatedVersion = negotiatedVersion;
		}

		@NonNull
		public Version getNegotiatedVersion() {
			return negotiatedVersion;
		}
	}
}
