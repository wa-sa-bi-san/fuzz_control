package com.fuzznug.fuzzcontrol;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.function.IntConsumer;

/** Touch previews locally; only release commits a USB change. */
public final class VerticalFader extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int value, start;
    private boolean dragging;
    private final IntConsumer preview, commit;
    public VerticalFader(Context context,int value,IntConsumer preview,IntConsumer commit) {
        super(context); this.value=value;this.preview=preview;this.commit=commit;
        setLayerType(View.LAYER_TYPE_SOFTWARE,null);setFocusable(true);setContentDescription("Mixer level");
    }
    private float pad(){return 24*getResources().getDisplayMetrics().density;}
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);float p=pad(),x=getWidth()/2f,h=getHeight()-2*p;
        paint.clearShadowLayer();paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(8*getResources().getDisplayMetrics().density);paint.setColor(Color.rgb(58,69,88));c.drawLine(x,p,x,p+h,paint);
        float y=p+h*(1-value/54f);
        paint.setStrokeWidth(3*getResources().getDisplayMetrics().density);paint.setShadowLayer(12*getResources().getDisplayMetrics().density,0,0,0xff41e7be);paint.setColor(isEnabled()?Color.rgb(92,224,182):Color.GRAY);c.drawLine(x,y,x,p+h,paint);
        paint.clearShadowLayer();paint.setStrokeWidth(1);paint.setTextSize(11*getResources().getDisplayMetrics().scaledDensity);
        for(int db:new int[]{6,0,-12,-24,-36,-48}) {
            float ty=p+h*(1-(db+48)/54f);paint.setColor(Color.rgb(145,157,180));
            c.drawLine(x-12,ty,x+12,ty,paint);c.drawText(String.valueOf(db),8,ty+4,paint);
        }
        paint.setColor(isEnabled()?Color.rgb(220,234,243):Color.GRAY);
        float density=getResources().getDisplayMetrics().density;paint.setShadowLayer(8*density,0,0,isEnabled()?0xff55d9bf:Color.TRANSPARENT);c.drawRoundRect(x-18*density,y-8*density,x+18*density,y+8*density,5*density,5*density,paint);paint.clearShadowLayer();paint.setColor(0xff315668);c.drawLine(x-10*density,y,x+10*density,y,paint);
    }
    private void move(float y) {
        float span=Math.max(1,getHeight()-2*pad());
        value=Math.max(0,Math.min(54,Math.round((1-(y-pad())/span)*54)));
        preview.accept(value);invalidate();
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        if(!isEnabled())return false;
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: start=value;dragging=true;getParent().requestDisallowInterceptTouchEvent(true);move(event.getY());return true;
            case MotionEvent.ACTION_MOVE: if(dragging)move(event.getY());return true;
            case MotionEvent.ACTION_UP: if(dragging){move(event.getY());dragging=false;getParent().requestDisallowInterceptTouchEvent(false);performClick();commit.accept(value);}return true;
            case MotionEvent.ACTION_CANCEL: dragging=false;value=start;preview.accept(value);invalidate();getParent().requestDisallowInterceptTouchEvent(false);return true;
            default:return false;
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,-48,6,value-48));
        if(isEnabled()){info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);}
    }
    @Override public boolean performAccessibilityAction(int action,Bundle args){
        if(!isEnabled())return super.performAccessibilityAction(action,args);
        int next=value;
        if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)next++;
        else if(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)next--;
        else if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && args!=null)
            next=Math.round(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE))+48;
        else return super.performAccessibilityAction(action,args);
        value=Math.max(0,Math.min(54,next));preview.accept(value);invalidate();commit.accept(value);return true;
    }
}
