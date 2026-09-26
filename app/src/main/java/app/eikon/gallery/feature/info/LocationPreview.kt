package app.eikon.gallery.feature.info

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.eikon.gallery.R
import app.eikon.gallery.domain.GeoPoint
import app.eikon.gallery.domain.places.MapProjection
import app.eikon.gallery.domain.places.MapViewport
import app.eikon.gallery.domain.places.WorldMap
import app.eikon.gallery.feature.places.drawCountries
import app.eikon.gallery.feature.places.ringBounds
import app.eikon.gallery.feature.places.ringPath

/** How much of the world the small map shows across: enough for the country around the point and a bit of its neighbours. */
private const val SPAN_DEGREES = 24.0

/**
 * A small map of where a photo was taken, drawn from the bundled country outlines and one dot: no map service, no tiles and no imagery, so the position is never sent anywhere. For
 * roads and places the user's own maps app is one tap away.
 */
@Composable
fun LocationPreview(world: WorldMap, point: GeoPoint, modifier: Modifier = Modifier) {
    val paths = remember(world) { world.rings.map(::ringPath) }
    val bounds = remember(world) { world.rings.map(::ringBounds) }
    val land = MaterialTheme.colorScheme.surfaceContainerHighest
    val border = MaterialTheme.colorScheme.outline
    val ocean = MaterialTheme.colorScheme.surfaceContainerLowest
    val marker = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.info_map_description)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ocean)
            .semantics { contentDescription = description },
    ) {
        val view = MapViewport(MapProjection.x(point.longitude), MapProjection.y(point.latitude), size.width / (SPAN_DEGREES / 360.0))
        drawCountries(paths, bounds, view, land, border)
        drawCircle(marker, radius = 6.dp.toPx(), center = Offset(size.width / 2, size.height / 2))
    }
}
