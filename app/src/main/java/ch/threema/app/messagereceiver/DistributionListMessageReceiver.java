/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2014-2023 Threema GmbH
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

package ch.threema.app.messagereceiver;

import android.content.Intent;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import ch.threema.app.ThreemaApplication;
import ch.threema.app.collections.Functional;
import ch.threema.app.collections.IPredicateNonNull;
import ch.threema.app.services.ContactService;
import ch.threema.app.services.DistributionListService;
import ch.threema.app.services.MessageService;
import ch.threema.app.utils.NameUtil;
import ch.threema.base.ThreemaException;
import ch.threema.base.crypto.SymmetricEncryptionResult;
import ch.threema.domain.protocol.ThreemaFeature;
import ch.threema.domain.protocol.csp.messages.ballot.BallotData;
import ch.threema.domain.protocol.csp.messages.ballot.BallotVote;
import ch.threema.storage.DatabaseServiceNew;
import ch.threema.storage.models.AbstractMessageModel;
import ch.threema.storage.models.ContactModel;
import ch.threema.storage.models.DistributionListMemberModel;
import ch.threema.storage.models.DistributionListMessageModel;
import ch.threema.storage.models.DistributionListModel;
import ch.threema.storage.models.MessageType;
import ch.threema.storage.models.ballot.BallotModel;
import ch.threema.storage.models.data.MessageContentsType;

public class DistributionListMessageReceiver implements MessageReceiver<DistributionListMessageModel> {
	private final List<ContactMessageReceiver> affectedMessageReceivers = new ArrayList<>();

	private final DatabaseServiceNew databaseServiceNew;
	private final ContactService contactService;
	private final DistributionListModel distributionListModel;
	private final DistributionListService distributionListService;

	public DistributionListMessageReceiver(
				DatabaseServiceNew databaseServiceNew,
				ContactService contactService,
				DistributionListModel distributionListModel,
				DistributionListService distributionListService) {
		this.databaseServiceNew = databaseServiceNew;
		this.contactService = contactService;
		this.distributionListModel = distributionListModel;
		this.distributionListService = distributionListService;

		for(ContactModel c: this.distributionListService.getMembers(this.distributionListModel)) {
			ContactMessageReceiver contactMessageReceiver = this.contactService.createReceiver(c);
			this.affectedMessageReceivers.add(new DistributionListContactMessageReceiver(contactMessageReceiver));
		}
	}


	public DistributionListModel getDistributionList() {
		return this.distributionListModel;
	}

	/**
	 * Return the {@link ContactMessageReceiver} instances that receive messages sent to this distribution list.
	 */
	@Override
	public @Nullable List<ContactMessageReceiver> getAffectedMessageReceivers() {
		return this.affectedMessageReceivers;
	}

	@Override
	public DistributionListMessageModel createLocalModel(final MessageType type, @MessageContentsType int messageContentsType, final Date postedAt) {
		DistributionListMessageModel m = new DistributionListMessageModel();
		m.setDistributionListId(this.getDistributionList().getId());
		m.setType(type);
		m.setMessageContentsType(messageContentsType);
		m.setPostedAt(postedAt);
		m.setCreatedAt(new Date());
		m.setSaved(false);
		m.setUid(UUID.randomUUID().toString());

		return m;
	}

	@Override
	@Deprecated
	public DistributionListMessageModel createAndSaveStatusModel(final String statusBody, final Date postedAt) {
		DistributionListMessageModel m = new DistributionListMessageModel(true);
		m.setDistributionListId(this.getDistributionList().getId());
		m.setType(MessageType.TEXT);
		m.setPostedAt(postedAt);
		m.setCreatedAt(new Date());
		m.setSaved(true);
		m.setUid(UUID.randomUUID().toString());
		m.setBody(statusBody);

		this.saveLocalModel(m);

		return m;
	}

	@Override
	public void saveLocalModel(final DistributionListMessageModel save) {
		this.databaseServiceNew.getDistributionListMessageModelFactory().createOrUpdate(save);
	}

	@Override
	public boolean createBoxedTextMessage(final String text, final DistributionListMessageModel messageModel) {
		return this.handleSendImage(messageModel);
	}

	@Override
	public boolean createBoxedLocationMessage(final DistributionListMessageModel messageModel) throws ThreemaException {
		return this.handleSendImage(messageModel);
	}

	private boolean handleSendImage(DistributionListMessageModel model) {
		model.setIsQueued(true);
		distributionListService.setIsArchived(distributionListModel, false);
		return true;
	}

	@Override
	public boolean createBoxedFileMessage(
		byte[] thumbnailBlobId,
		byte[] fileBlobId,
		SymmetricEncryptionResult encryptionResult,
		DistributionListMessageModel messageModel
	) {	//disabled
		for (ContactMessageReceiver receiver : affectedMessageReceivers) {
			if (receiver instanceof DistributionListContactMessageReceiver) {
				((DistributionListContactMessageReceiver) receiver).setFileMessageParameters(
					thumbnailBlobId, fileBlobId, encryptionResult
				);
			}
		}
		return this.handleSendImage(messageModel);
	}

	@Override
	public void createBoxedBallotMessage(
											BallotData ballotData,
											BallotModel ballotModel,
											final String[] filteredIdentities,
											DistributionListMessageModel abstractMessageModel) {
		// Not supported in distribution lists
	}

	@Override
	public void createBoxedBallotVoteMessage(BallotVote[] votes, BallotModel ballotModel) {
		// Not supported in distribution lists
	}

	@Override
	public List<DistributionListMessageModel> loadMessages(MessageService.MessageFilter filter) {
		return this.databaseServiceNew.getDistributionListMessageModelFactory().find(
				this.distributionListModel.getId(),
				filter
		);
	}

	@Override
	public long getMessagesCount() {
		return this.databaseServiceNew.getDistributionListMessageModelFactory().countMessages(
			this.distributionListModel.getId());
	}

	@Override
	public long getUnreadMessagesCount() {
		return 0;
	}

	@Override
	public List<DistributionListMessageModel> getUnreadMessages() {
		return null;
	}

	@Override
	public boolean isEqual(MessageReceiver o) {
		return o instanceof DistributionListMessageReceiver && ((DistributionListMessageReceiver)o).getDistributionList().getId() == this.getDistributionList().getId();
	}

	@Override
	public String getDisplayName() {
		return NameUtil.getDisplayName(this.getDistributionList(), this.distributionListService);
	}

	@Override
	public String getShortName() {
		return getDisplayName();
	}

	@Override
	public void prepareIntent(Intent intent) {
		intent.putExtra(ThreemaApplication.INTENT_DATA_DISTRIBUTION_LIST, this.getDistributionList().getId());
	}

	@Override
	public Bitmap getNotificationAvatar() {
		return distributionListService.getAvatar(distributionListModel, false);
	}

	@Override
	public Bitmap getAvatar() {
		return distributionListService.getAvatar(distributionListModel, true, true);
	}

	@Deprecated
	@Override
	public int getUniqueId() {
		return 0;
	}

	@Override
	public String getUniqueIdString() {
		return this.distributionListService.getUniqueIdString(this.distributionListModel);
	}

	@Override
	public boolean isMessageBelongsToMe(AbstractMessageModel message) {
		return
				message instanceof DistributionListMessageModel
				&& ((DistributionListMessageModel)message).getDistributionListId() == this.getDistributionList().getId();
	}

	@Override
	public boolean sendMediaData() {
		return true;
	}

	@Override
	public boolean offerRetry() {
		return false;
	}

	@Override
	public boolean validateSendingPermission(OnSendingPermissionDenied onSendingPermissionDenied) {
		return this.distributionListModel != null;
	}

	@Override
	@MessageReceiverType
	public int getType() {
		return Type_DISTRIBUTION_LIST;
	}

	@Override
	public String[] getIdentities() {
		return this.distributionListService.getDistributionListIdentities(this.distributionListModel);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof DistributionListMessageReceiver)) return false;
		DistributionListMessageReceiver that = (DistributionListMessageReceiver) o;
		return Objects.equals(affectedMessageReceivers, that.affectedMessageReceivers) && Objects.equals(distributionListModel, that.distributionListModel);
	}

	@Override
	public int hashCode() {
		return Objects.hash(affectedMessageReceivers, distributionListModel);
	}
}
