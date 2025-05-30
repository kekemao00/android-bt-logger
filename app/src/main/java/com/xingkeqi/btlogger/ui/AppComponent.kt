/*
 * Copyright 2021 Md. Mahmudul Hasan Shohag
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ------------------------------------------------------------------------
 *
 * Project: Why Not Compose!
 * Developed by: @ImaginativeShohag
 *
 * Md. Mahmudul Hasan Shohag
 * imaginativeshohag@gmail.com
 *
 * Source: https://github.com/ImaginativeShohag/Why-Not-Compose
 */

package com.xingkeqi.btlogger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

object AppComponent {

    @Composable
    fun Header(
        text: String,
        modifier: Modifier = Modifier,
    ) {
        Text(
            modifier = modifier
                .padding(
                    start = 16.dp,
                    top = 32.dp,
                    end = 16.dp,
                    bottom = 32.dp
                )
                .fillMaxWidth(),
            text = text,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
    }

    @Composable
    fun SubHeader(
        text: String,
        modifier: Modifier = Modifier,
    ) {
        Text(
            modifier = modifier
                .padding(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp
                )
                .fillMaxWidth(),
            text = text,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }

    @Composable
    fun MediumSpacer(
        modifier: Modifier = Modifier,
    ) {
        Spacer(
            modifier = modifier
                .fillMaxWidth()
                .height(16.dp)
        )
    }

    @Composable
    fun BigSpacer(
        modifier: Modifier = Modifier,
    ) {
        Spacer(
            modifier = modifier
                .fillMaxWidth()
                .height(32.dp)
        )
    }

    @Composable
    fun CustomListItem(
        text: String,
        modifier: Modifier = Modifier,
    ) {
        Text(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp, 4.dp)
                .shadow(2.dp, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .clickable {
                    // do things here.
                }
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp, 8.dp),
            text = text,
            textAlign = TextAlign.Center,
        )
    }
}
