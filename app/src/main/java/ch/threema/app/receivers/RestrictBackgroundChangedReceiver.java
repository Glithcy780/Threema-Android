/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2013-2023 Threema GmbH
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

package ch.threema.app.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import org.slf4j.Logger;

import ch.threema.app.workers.RestrictBackgroundChangedWorker;
import ch.threema.base.utils.LoggingUtil;

import static ch.threema.app.ThreemaApplication.WORKER_RESTRICT_BACKGROUND_CHANGED;

public class RestrictBackgroundChangedReceiver extends BroadcastReceiver {
	private static final Logger logger = LoggingUtil.getThreemaLogger("RestrictBackgroundChangedReceiver");

	@Override
	public void onReceive(Context context, Intent intent) {
		logger.info("Restrict Background changed broadcast received");
		OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(RestrictBackgroundChangedWorker.class)
			.build();
		WorkManager.getInstance(context).enqueueUniqueWork(WORKER_RESTRICT_BACKGROUND_CHANGED, ExistingWorkPolicy.REPLACE, workRequest);
	}
}
