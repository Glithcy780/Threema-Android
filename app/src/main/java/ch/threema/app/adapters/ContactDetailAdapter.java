/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2015-2023 Threema GmbH
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

package ch.threema.app.adapters;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.RequestManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import org.slf4j.Logger;

import java.util.List;

import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.dialogs.PublicKeyDialog;
import ch.threema.app.glide.AvatarOptions;
import ch.threema.app.managers.ServiceManager;
import ch.threema.app.services.ContactService;
import ch.threema.app.services.GroupService;
import ch.threema.app.services.IdListService;
import ch.threema.app.services.PreferenceService;
import ch.threema.app.services.UserService;
import ch.threema.app.ui.VerificationLevelImageView;
import ch.threema.app.utils.AndroidContactUtil;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.base.utils.LoggingUtil;
import ch.threema.protobuf.csp.e2e.fs.Terminate;
import ch.threema.storage.models.ContactModel;
import ch.threema.storage.models.GroupModel;

public class ContactDetailAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
	private static final Logger logger = LoggingUtil.getThreemaLogger("ContactDetailAdapter");

	private static final int TYPE_HEADER = 0;
	private static final int TYPE_ITEM = 1;

	private final Context context;
	private ContactService contactService;
	private GroupService groupService;
	private UserService userService;
	private PreferenceService preferenceService;
	private IdListService excludeFromSyncListService;
	private IdListService blackListIdentityService;
	private final ContactModel contactModel;
	private final List<GroupModel> values;
	private OnClickListener onClickListener;
	private final @NonNull RequestManager requestManager;

	public static class ItemHolder extends RecyclerView.ViewHolder {
		public final View view;
		public final TextView nameView;
		public final ImageView avatarView, statusView;

		public ItemHolder(View view) {
			super(view);
			this.view = view;
			this.nameView = itemView.findViewById(R.id.contact_name);
			this.avatarView = itemView.findViewById(R.id.contact_avatar);
			this.statusView = itemView.findViewById(R.id.status);
		}
	}

	public class HeaderHolder extends RecyclerView.ViewHolder {
		private final VerificationLevelImageView verificationLevelImageView;
		private final TextView threemaIdView;
		private final MaterialSwitch synchronize;
		private final View nicknameContainer, synchronizeContainer;
		private final ImageView syncSourceIcon;
		private final TextView publicNickNameView;
		private final LinearLayout groupMembershipTitle;
		private final MaterialAutoCompleteTextView readReceiptsSpinner, typingIndicatorsSpinner;
		private final View clearForwardSecuritySection;
		private final MaterialButton clearForwardSecurityButton;
		private int onThreemaIDClickCount = 0;

		public HeaderHolder(View view) {
			super(view);

			this.threemaIdView = itemView.findViewById(R.id.threema_id);
			this.verificationLevelImageView = itemView.findViewById(R.id.verification_level_image);
			ImageView verificationLevelIconView = itemView.findViewById(R.id.verification_information_icon);
			this.synchronize = itemView.findViewById(R.id.synchronize_contact);
			this.synchronizeContainer = itemView.findViewById(R.id.synchronize_contact_container);
			this.nicknameContainer = itemView.findViewById(R.id.nickname_container);
			this.publicNickNameView = itemView.findViewById(R.id.public_nickname);
			this.groupMembershipTitle = itemView.findViewById(R.id.group_members_title_container);
			this.syncSourceIcon = itemView.findViewById(R.id.sync_source_icon);
			this.readReceiptsSpinner = itemView.findViewById(R.id.read_receipts_spinner);
			this.typingIndicatorsSpinner = itemView.findViewById(R.id.typing_indicators_spinner);
			this.clearForwardSecuritySection = itemView.findViewById(R.id.clear_forward_security_section);
			this.clearForwardSecurityButton = itemView.findViewById(R.id.clear_forward_security);

			verificationLevelIconView.setOnClickListener(v -> {
				if (onClickListener != null) {
					onClickListener.onVerificationInfoClick(v);
				}
			});

			threemaIdView.setOnLongClickListener(ignored -> {
				String identity = contactModel.getIdentity();
				copyTextToClipboard(identity, R.string.contact_details_id_copied);
				return true;
			});

			// When clicking ten times on the Threema ID, the clear forward security session button
			// becomes visible.
			threemaIdView.setOnClickListener(v -> {
				onThreemaIDClickCount++;
				if (onThreemaIDClickCount >= 10) {
					onThreemaIDClickCount = 0;
					clearForwardSecuritySection.setVisibility(View.VISIBLE);
					clearForwardSecurityButton.setOnClickListener(clearButton -> {
						try {
							ThreemaApplication.requireServiceManager()
								.getForwardSecurityMessageProcessor()
								.clearAndTerminateAllSessions(contactModel, Terminate.Cause.RESET);
							Toast.makeText(clearButton.getContext(), R.string.forward_security_cleared, Toast.LENGTH_LONG).show();
						} catch (Exception e) {
							Toast.makeText(clearButton.getContext(), e.getMessage(), Toast.LENGTH_LONG).show();
						}
					});
				}
			});

			publicNickNameView.setOnLongClickListener(ignored -> {
				String nickname = contactModel.getPublicNickName();
				copyTextToClipboard(nickname, R.string.contact_details_nickname_copied);
				return true;
			});


			itemView.findViewById(R.id.public_key_button).setOnClickListener(v -> {
				if (context instanceof AppCompatActivity) {
					PublicKeyDialog.newInstance(context.getString(R.string.public_key_for, contactModel.getIdentity()), contactModel.getPublicKey())
						.show(((AppCompatActivity) context).getSupportFragmentManager(), "pk");
				}
			});
		}

		private void copyTextToClipboard(String data, @StringRes int toastResId) {
			ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
			ClipData clip = ClipData.newPlainText(null, data);
			clipboard.setPrimaryClip(clip);
			Toast.makeText(context, toastResId, Toast.LENGTH_SHORT).show();
		}
	}

	public ContactDetailAdapter(
		Context context,
		List<GroupModel>values,
		ContactModel contactModel,
		@NonNull RequestManager requestManager
	) {
		this.context = context;
		this.values = values;
		this.contactModel = contactModel;
		this.requestManager = requestManager;

		try {
			ServiceManager serviceManager = ThreemaApplication.requireServiceManager();
			this.contactService = serviceManager.getContactService();
			this.groupService = serviceManager.getGroupService();
			this.userService = serviceManager.getUserService();
			this.excludeFromSyncListService = serviceManager.getExcludedSyncIdentitiesService();
			this.blackListIdentityService = serviceManager.getBlackListService();
			this.preferenceService = serviceManager.getPreferenceService();
		} catch (Exception e) {
			logger.error("Exception", e);
		}
	}

	@NonNull
	@Override
	public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		if (viewType == TYPE_ITEM) {
			View v = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_contact_detail, parent, false);

			return new ItemHolder(v);
		} else if (viewType == TYPE_HEADER) {
			View v = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.header_contact_detail, parent, false);

			return new HeaderHolder(v);
		}
		throw new RuntimeException("no matching item type");
	}

	@Override
	public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
		if (holder instanceof ItemHolder) {
			ItemHolder itemHolder = (ItemHolder) holder;
			final GroupModel groupModel = getItem(position);

			this.groupService.loadAvatarIntoImage(
				groupModel,
				itemHolder.avatarView,
				AvatarOptions.PRESET_DEFAULT_FALLBACK,
				requestManager
			);

			String groupName = groupModel.getName();
			if (groupName == null) {
				groupName = groupService.getMembersString(groupModel);
			}
			itemHolder.nameView.setText(groupName);

			if (groupService.isGroupCreator(groupModel)) {
				itemHolder.statusView.setImageResource(R.drawable.ic_group_outline);
			} else {
				itemHolder.statusView.setImageResource(R.drawable.ic_group_filled);
			}
			itemHolder.view.setOnClickListener(v -> onClickListener.onItemClick(v, groupModel));
		} else {
			HeaderHolder headerHolder = (HeaderHolder) holder;

			String identityAdditional = null;
			if (this.contactModel.getState() != null) {
				switch (this.contactModel.getState()) {
					case ACTIVE:
						if (blackListIdentityService.has(contactModel.getIdentity())) {
							identityAdditional = context.getString(R.string.blocked);
						}
						break;
					case INACTIVE:
						identityAdditional = context.getString(R.string.contact_state_inactive);
						break;
					case INVALID:
						identityAdditional = context.getString(R.string.contact_state_invalid);
						break;
				}
			}
			headerHolder.threemaIdView.setText(contactModel.getIdentity() + (identityAdditional != null ? " (" + identityAdditional + ")" : ""));
			headerHolder.verificationLevelImageView.setContactModel(contactModel);
			headerHolder.verificationLevelImageView.setVisibility(View.VISIBLE);

			boolean isSyncExcluded = excludeFromSyncListService.has(contactModel.getIdentity());

			if (preferenceService.isSyncContacts()
				&& (contactModel.getAndroidContactLookupKey() != null || isSyncExcluded)
				&& ConfigUtils.isPermissionGranted(ThreemaApplication.getAppContext(), Manifest.permission.READ_CONTACTS)
			) {
				headerHolder.synchronizeContainer.setVisibility(View.VISIBLE);

				Drawable icon = null;
				try {
					icon = AndroidContactUtil.getInstance().getAccountIcon(contactModel);
				} catch (SecurityException e) {
					logger.error("Could not access android account icon", e);
				}
				if (icon != null) {
					headerHolder.syncSourceIcon.setImageDrawable(icon);
					headerHolder.syncSourceIcon.setVisibility(View.VISIBLE);
				} else {
					headerHolder.syncSourceIcon.setVisibility(View.GONE);
				}

				headerHolder.synchronize.setChecked(isSyncExcluded);
				headerHolder.synchronize.setOnCheckedChangeListener((buttonView, isChecked) -> {
					if (isChecked) {
						excludeFromSyncListService.add(contactModel.getIdentity());
					} else {
						excludeFromSyncListService.remove(contactModel.getIdentity());
					}
				});
			} else {
				headerHolder.synchronizeContainer.setVisibility(View.GONE);
			}

			String nicknameString = contactModel.getPublicNickName();
			if (nicknameString != null && nicknameString.length() > 0) {
				headerHolder.publicNickNameView.setText(contactModel.getPublicNickName());
			} else {
				headerHolder.nicknameContainer.setVisibility(View.GONE);
			}

			if (values.size() > 0) {
				headerHolder.groupMembershipTitle.setVisibility(View.VISIBLE);
			} else {
				headerHolder.groupMembershipTitle.setVisibility(View.GONE);
			}

			final String[] choices = context.getResources().getStringArray(R.array.receipts_override_choices);
			choices[0] = context.getString(R.string.receipts_override_choice_default,
				choices[preferenceService.isReadReceipts() ? 1 : 2]);

			ArrayAdapter<String> readReceiptsAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, choices);
			headerHolder.readReceiptsSpinner.setAdapter(readReceiptsAdapter);
			headerHolder.readReceiptsSpinner.setText(choices[contactModel.getReadReceipts()], false);
			headerHolder.readReceiptsSpinner.setOnItemClickListener((parent, view, position1, id) -> {
				contactModel.setReadReceipts(position1);
				contactService.save(contactModel);
			});

			final String[] typingChoices = context.getResources().getStringArray(R.array.receipts_override_choices);
			typingChoices[0] = context.getString(R.string.receipts_override_choice_default,
				typingChoices[preferenceService.isTypingIndicator() ? 1 : 2]);

			ArrayAdapter<String> typingIndicatorAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, typingChoices);
			headerHolder.typingIndicatorsSpinner.setAdapter(typingIndicatorAdapter);
			headerHolder.typingIndicatorsSpinner.setText(typingChoices[contactModel.getTypingIndicators()], false);
			headerHolder.typingIndicatorsSpinner.setOnItemClickListener((parent, view, position12, id) -> {
				contactModel.setTypingIndicators(position12);
				contactService.save(contactModel);
			});
		}
	}

	@Override
	public int getItemCount() {
		return values.size() + 1;
	}

	@Override
	public int getItemViewType(int position) {
		if (isPositionHeader(position)) {
			return TYPE_HEADER;
		}
		return TYPE_ITEM;
	}

	private boolean isPositionHeader(int position) {
		return position == 0;
	}

	private GroupModel getItem(int position) {
		return values.get(position - 1);
	}

	public void setOnClickListener(OnClickListener listener) {
		onClickListener = listener;
	}

	public interface OnClickListener {
		void onItemClick(View v, GroupModel groupModel);

		void onVerificationInfoClick(View v);
	}

}
