package com.airbnb.android.react.maps;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.LayoutShadowNode;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.ViewGroupManager;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

public class AirMapMarkerManager extends ViewGroupManager<AirMapMarker> {

  private static final int SHOW_INFO_WINDOW = 1;
  private static final int HIDE_INFO_WINDOW = 2;
  private static final int ANIMATE_MARKER_TO_COORDINATE = 3;

  public static class AirMapMarkerSharedIcon {

    // this is the bitmap descriptor for the image
    private BitmapDescriptor iconBitmapDescriptor;

    // this is the bitmap for the image
    private Bitmap bitmap;

    // this is the list of markers that shared the same image
    private List<WeakReference<AirMapMarker>> markers;

    // this is the map to store texted bitmap descriptor, indexed by the corresponding text.
    private Map<String, BitmapDescriptor> iconBitmapDescriptorsWithText;

    // this is the map to store the corresponding markers that displays the marker text
    // i.e. we allows different texts for same image
    private Map<String, List<WeakReference<AirMapMarker>>> markersWithText;

    // this is to denote whether the image fetching has started by any of the markers
    // if it is already started, we do not need to fetch again.
    private boolean loadImageStarted;

    public AirMapMarkerSharedIcon(String uri) {
      this.markers = new ArrayList<>();
      this.markersWithText = new HashMap<>();
      this.iconBitmapDescriptorsWithText = new HashMap<>();
      this.loadImageStarted = false;
    }

    // to check whether the image fetching process is started.
    public synchronized boolean shouldLoadImage() {
      if (!this.loadImageStarted) {
        this.loadImageStarted = true;
        return true;
      }
      return false;
    }

    /**
     * subscribe icon update for given marker.
     * <p>
     * The marker is wrapped in weakReference, so no need to remove it explicitly.
     *
     * @param marker
     */
    public void addMarker(AirMapMarker marker) {
      synchronized (this) {
        this.addToList(marker, this.markers);
      }
      if (this.iconBitmapDescriptor != null) {
        marker.setIconBitmapDescriptor(this.iconBitmapDescriptor, this.bitmap);
      }
    }

    /**
     * Subscribe icon update for given marker with the corresponding marker text.
     *
     * @param marker
     * @param markerText
     */
    public void addMarker(AirMapMarker marker, String markerText) {

      this.addMarker(marker);

      // we need to add this marker into the corresponding list.
      List<WeakReference<AirMapMarker>> markersWithSameText =
          this.markersWithText.get(markerText);

      if (markersWithSameText == null) {
        markersWithSameText = new ArrayList<>();
        this.markersWithText.put(markerText, markersWithSameText);
      }

      this.addToList(marker, markersWithSameText);

      BitmapDescriptor iconWithText = this.iconBitmapDescriptorsWithText.get(markerText);
      if (iconWithText != null) {
        // we already have a texted bitmap desriptor, we use it directly.
        marker.setIconBitmapDescriptorWithText(iconWithText);
      }
    }

    /**
     * update the bitmap descriptor and bitmap when the marker that fetch the image finished.
     *
     * This method will as well notify all the updated bitmap descriptor to all markers.
     * @param bitmapDescriptor
     * @param bitmap
     */
    public synchronized void updateIcon(BitmapDescriptor bitmapDescriptor, Bitmap bitmap) {
      this.iconBitmapDescriptor = bitmapDescriptor;
      // we need this bitmap to be mutable in order for canvas to draw on it
      this.bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
      List<WeakReference<AirMapMarker>> newArray = new ArrayList<>();
      for (WeakReference<AirMapMarker> weakMarker : markers) {
        AirMapMarker marker = weakMarker.get();
        if (marker != null) {
          marker.setIconBitmapDescriptor(bitmapDescriptor, bitmap);
          newArray.add(weakMarker);
        }
      }
      this.markers = newArray;
    }

    /**
     * Get the texted bitmap descriptor with given marker text.
     *
     * If it is not already existed, we draw it and cache it.
     * @param markerText
     * @return
     */
    public synchronized BitmapDescriptor getTextedIcon(String markerText) {
      if (markerText == null) {
        return this.iconBitmapDescriptor;
      }
      BitmapDescriptor result;
      if ((result = this.iconBitmapDescriptorsWithText.get(markerText)) != null) {
        return result;
      }
      if (this.bitmap == null) {
        return this.iconBitmapDescriptor;
      }

      result = this.drawTextOnBitmap(markerText, this.bitmap);
      this.iconBitmapDescriptorsWithText.put(markerText, result);
      return result;
    }

    /**
     * helper method to draw text on bitmap.
     *
     * currently it uses some hardcoded parameters.
     * if we want to have custom parameters, we can't cache bitmap result by marker text.
     *
     * one possible solution is to create another markerTextKey prop to identify them. And if
     * not provided, we use markerText as key directly.
     *
     * @param text
     * @param bitmap
     * @return
     */
    private BitmapDescriptor drawTextOnBitmap(String text, Bitmap bitmap) {
      if (text != null && text.length() > 0) {
        Bitmap newBitmap = Bitmap.createBitmap(bitmap);
        newBitmap = newBitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(newBitmap);
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        // calculate the margin left value,
        // and adjust the text size to make it fit into the marker, as much as possible
        float textSize = 30;
        paint.setTextSize(textSize);
        float textWidth = paint.measureText(text);
        float bitmapWidth = Math.max(bitmap.getWidth(), 0);
        while (bitmapWidth < textWidth && textSize >= 15) {
          // reducing the text size until it is 15 point or text can be fit into the marker.
          textSize -= 3;
          paint.setTextSize(textSize);
          textWidth = paint.measureText(text);
        }
        // it is ok to be negative here.
        float leftMargin = (bitmapWidth - textWidth) / 2;
        int height = bitmap.getHeight();
        canvas.drawText(text, leftMargin, height / 2, paint);
        return BitmapDescriptorFactory.fromBitmap(newBitmap);
      } else {
        return BitmapDescriptorFactory.fromBitmap(bitmap);
      }
    }

    /**
     * helper method to wrap a marker using WeakReference and add it
     * into a list if it is not already added.
     *
     * We use weak reference because we don't want to hold the marker if it is been removed from map.
     * We don't want to have manual remove needed.
     *
     * @param marker
     * @param list
     */
    private void addToList(AirMapMarker marker, List<WeakReference<AirMapMarker>> list) {
      for (WeakReference<AirMapMarker> weakMarkers : list) {
        if (weakMarkers.get() == marker) {
          return;
        }
      }
      list.add(new WeakReference<AirMapMarker>(marker));
    }
  }

  private Map<String, AirMapMarkerSharedIcon> sharedIcons = new ConcurrentHashMap<>();

  /**
   * get the shared icon object, if not existed, create a new one and store it.
   *
   * @param uri
   * @return the icon object for the given uri.
   */
  public AirMapMarkerSharedIcon getSharedIcon(String uri) {
    if (uri == null) {
      return null;
    }
    AirMapMarkerSharedIcon icon = this.sharedIcons.get(uri);
    if (icon == null) {
      icon = new AirMapMarkerSharedIcon(uri);
      this.sharedIcons.put(uri, icon);
    }
    return icon;
  }

  public AirMapMarkerManager() {
  }

  @Override
  public String getName() {
    return "AIRMapMarker";
  }

  @Override
  public AirMapMarker createViewInstance(ThemedReactContext context) {
    return new AirMapMarker(context, this);
  }

  @ReactProp(name = "coordinate")
  public void setCoordinate(AirMapMarker view, ReadableMap map) {
    view.setCoordinate(map);
  }

  @ReactProp(name = "title")
  public void setTitle(AirMapMarker view, String title) {
    view.setTitle(title);
  }

  @ReactProp(name = "identifier")
  public void setIdentifier(AirMapMarker view, String identifier) {
    view.setIdentifier(identifier);
  }

  @ReactProp(name = "description")
  public void setDescription(AirMapMarker view, String description) {
    view.setSnippet(description);
  }

  // NOTE(lmr):
  // android uses normalized coordinate systems for this, and is provided through the
  // `anchor` property  and `calloutAnchor` instead.  Perhaps some work could be done
  // to normalize iOS and android to use just one of the systems.
//    @ReactProp(name = "centerOffset")
//    public void setCenterOffset(AirMapMarker view, ReadableMap map) {
//
//    }
//
//    @ReactProp(name = "calloutOffset")
//    public void setCalloutOffset(AirMapMarker view, ReadableMap map) {
//
//    }

  @ReactProp(name = "anchor")
  public void setAnchor(AirMapMarker view, ReadableMap map) {
    // should default to (0.5, 1) (bottom middle)
    double x = map != null && map.hasKey("x") ? map.getDouble("x") : 0.5;
    double y = map != null && map.hasKey("y") ? map.getDouble("y") : 1.0;
    view.setAnchor(x, y);
  }

  @ReactProp(name = "calloutAnchor")
  public void setCalloutAnchor(AirMapMarker view, ReadableMap map) {
    // should default to (0.5, 0) (top middle)
    double x = map != null && map.hasKey("x") ? map.getDouble("x") : 0.5;
    double y = map != null && map.hasKey("y") ? map.getDouble("y") : 0.0;
    view.setCalloutAnchor(x, y);
  }

  @ReactProp(name = "image")
  public void setImage(AirMapMarker view, @Nullable String source) {
    view.setImage(source);
  }
//    public void setImage(AirMapMarker view, ReadableMap image) {
//        view.setImage(image);
//    }

  @ReactProp(name = "pinColor", defaultInt = Color.RED, customType = "Color")
  public void setPinColor(AirMapMarker view, int pinColor) {
    float[] hsv = new float[3];
    Color.colorToHSV(pinColor, hsv);
    // NOTE: android only supports a hue
    view.setMarkerHue(hsv[0]);
  }

  @ReactProp(name = "rotation", defaultFloat = 0.0f)
  public void setMarkerRotation(AirMapMarker view, float rotation) {
    view.setRotation(rotation);
  }

  @ReactProp(name = "flat", defaultBoolean = false)
  public void setFlat(AirMapMarker view, boolean flat) {
    view.setFlat(flat);
  }

  @ReactProp(name = "draggable", defaultBoolean = false)
  public void setDraggable(AirMapMarker view, boolean draggable) {
    view.setDraggable(draggable);
  }

  @Override
  @ReactProp(name = "zIndex", defaultFloat = 0.0f)
  public void setZIndex(AirMapMarker view, float zIndex) {
    super.setZIndex(view, zIndex);
    int integerZIndex = Math.round(zIndex);
    view.setZIndex(integerZIndex);
  }

  @Override
  @ReactProp(name = "opacity", defaultFloat = 1.0f)
  public void setOpacity(AirMapMarker view, float opacity) {
    super.setOpacity(view, opacity);
    view.setOpacity(opacity);
  }

  @ReactProp(name = "markerText")
  public void setMarkerText(AirMapMarker view, String markerText) {
    view.setMarkerText(markerText);
  }

  @Override
  public void addView(AirMapMarker parent, View child, int index) {
    // if an <Callout /> component is a child, then it is a callout view, NOT part of the
    // marker.
    if (child instanceof AirMapCallout) {
      parent.setCalloutView((AirMapCallout) child);
    } else {
      super.addView(parent, child, index);
      parent.update();
    }
  }

  @Override
  public void removeViewAt(AirMapMarker parent, int index) {
    super.removeViewAt(parent, index);
    parent.update();
  }

  @Override
  @Nullable
  public Map<String, Integer> getCommandsMap() {
    return MapBuilder.of(
        "showCallout", SHOW_INFO_WINDOW,
        "hideCallout", HIDE_INFO_WINDOW,
        "animateMarkerToCoordinate",  ANIMATE_MARKER_TO_COORDINATE
    );
  }

  @Override
  public void receiveCommand(AirMapMarker view, int commandId, @Nullable ReadableArray args) {
    Integer duration;
    Double lat;
    Double lng;
    ReadableMap region;

    switch (commandId) {
      case SHOW_INFO_WINDOW:
        ((Marker) view.getFeature()).showInfoWindow();
        break;

      case HIDE_INFO_WINDOW:
        ((Marker) view.getFeature()).hideInfoWindow();
        break;
      
      case ANIMATE_MARKER_TO_COORDINATE:
        region = args.getMap(0);
        duration = args.getInt(1);

        lng = region.getDouble("longitude");
        lat = region.getDouble("latitude");
        view.animateToCoodinate(new LatLng(lat, lng), duration);
        break;
    }
  }

  @Override
  @Nullable
  public Map getExportedCustomDirectEventTypeConstants() {
    Map<String, Map<String, String>> map = MapBuilder.of(
        "onPress", MapBuilder.of("registrationName", "onPress"),
        "onCalloutPress", MapBuilder.of("registrationName", "onCalloutPress"),
        "onDragStart", MapBuilder.of("registrationName", "onDragStart"),
        "onDrag", MapBuilder.of("registrationName", "onDrag"),
        "onDragEnd", MapBuilder.of("registrationName", "onDragEnd")
    );

    map.putAll(MapBuilder.of(
        "onDragStart", MapBuilder.of("registrationName", "onDragStart"),
        "onDrag", MapBuilder.of("registrationName", "onDrag"),
        "onDragEnd", MapBuilder.of("registrationName", "onDragEnd")
    ));

    return map;
  }

  @Override
  public LayoutShadowNode createShadowNodeInstance() {
    // we use a custom shadow node that emits the width/height of the view
    // after layout with the updateExtraData method. Without this, we can't generate
    // a bitmap of the appropriate width/height of the rendered view.
    return new SizeReportingShadowNode();
  }

  @Override
  public void updateExtraData(AirMapMarker view, Object extraData) {
    // This method is called from the shadow node with the width/height of the rendered
    // marker view.
    HashMap<String, Float> data = (HashMap<String, Float>) extraData;
    float width = data.get("width");
    float height = data.get("height");
    view.update((int) width, (int) height);
  }
}
