package uk.co.rovcompanion;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Small native views for connection motion and the live travel overview. */
final class StudioViews {
    static final int BLUE=Color.rgb(55,89,237), INK=Color.rgb(25,30,44), MUTED=Color.rgb(115,124,145);
    static class Rail extends View {
        private final Paint paint=new Paint(3);
        private int start=5,end=95;
        private float position=-1;
        Rail(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void range(int a,int b,float position){start=a;end=b;this.position=position;invalidate();}
        protected void onDraw(Canvas c){
            super.onDraw(c);float d=getResources().getDisplayMetrics().density;
            float x=12*d,w=getWidth()-24*d,y=getHeight()*.55f;
            paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(6*d);paint.setColor(0xffe6e9f1);c.drawLine(x,y,x+w,y,paint);
            paint.setColor(BLUE);c.drawLine(x+w*start/100f,y,x+w*end/100f,y,paint);
            paint.setColor(BLUE);c.drawCircle(x+w*start/100f,y,5*d,paint);c.drawCircle(x+w*end/100f,y,5*d,paint);
            if(position>=0){float p=Math.max(0,Math.min(100,position));paint.setColor(INK);c.drawCircle(x+w*p/100f,y,10*d,paint);paint.setColor(Color.WHITE);c.drawCircle(x+w*p/100f,y,4*d,paint);}
        }
    }
    static class Pulse extends View {
        private final Paint paint=new Paint(3);
        private float phase;
        private ValueAnimator animator;
        Pulse(Context c){super(c);setContentDescription("Searching for your slider");}
        void active(boolean active){
            if(active&&animator!=null)return;
            if(animator!=null){animator.cancel();animator=null;}
            if(!active)return;
            animator=ValueAnimator.ofFloat(0,1);animator.setDuration(1700);animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(a->{phase=(float)a.getAnimatedValue();invalidate();});animator.start();
        }
        protected void onDetachedFromWindow(){active(false);super.onDetachedFromWindow();}
        protected void onDraw(Canvas c){
            float r=Math.min(getWidth(),getHeight())*.44f,cx=getWidth()/2f,cy=getHeight()/2f;
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);
            for(int i=0;i<2;i++){float p=(phase+i*.5f)%1;paint.setColor(BLUE);paint.setAlpha((int)(65*(1-p)));c.drawCircle(cx,cy,r*(.45f+.55f*p),paint);}
            paint.setStyle(Paint.Style.FILL);paint.setColor(0xffedf1ff);paint.setAlpha(255);c.drawCircle(cx,cy,r*.48f,paint);
            paint.setColor(BLUE);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(r*.055f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            Path b=new Path();b.moveTo(cx,cy-r*.27f);b.lineTo(cx,cy+r*.27f);b.lineTo(cx+r*.18f,cy+r*.12f);b.lineTo(cx-r*.17f,cy-r*.13f);b.moveTo(cx,cy-r*.27f);b.lineTo(cx+r*.18f,cy-r*.12f);b.lineTo(cx-r*.17f,cy+r*.13f);c.drawPath(b,paint);paint.setStyle(Paint.Style.FILL);
        }
    }
    static class Check extends View {
        private final Paint paint=new Paint(3);
        private float progress;
        private ValueAnimator animator;
        Check(Context c){super(c);setContentDescription("Slider connected");}
        void reveal(){
            if(animator!=null)animator.cancel();
            if(!ValueAnimator.areAnimatorsEnabled()){progress=1;invalidate();return;}
            animator=ValueAnimator.ofFloat(0,1);animator.setDuration(560);animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(a->{progress=(float)a.getAnimatedValue();invalidate();});animator.start();
        }
        protected void onDetachedFromWindow(){if(animator!=null)animator.cancel();super.onDetachedFromWindow();}
        protected void onDraw(Canvas c){
            float r=Math.min(getWidth(),getHeight())*.39f,cx=getWidth()/2f,cy=getHeight()/2f;
            paint.setColor(0xffe5f7ef);c.drawCircle(cx,cy,r*1.25f,paint);
            paint.setColor(0xff18a776);c.drawCircle(cx,cy,r*(.8f+.2f*progress),paint);
            Path tick=new Path();tick.moveTo(cx-r*.44f,cy);tick.lineTo(cx-r*.08f,cy+r*.32f);tick.lineTo(cx+r*.48f,cy-r*.32f);
            PathMeasure measure=new PathMeasure(tick,false);Path visible=new Path();measure.getSegment(0,measure.getLength()*Math.max(0,(progress-.1f)/.9f),visible,true);
            paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(r*.13f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);c.drawPath(visible,paint);paint.setStyle(Paint.Style.FILL);
        }
    }
    static class More extends View {
        private final Paint paint=new Paint(3);
        More(Context c){super(c);paint.setColor(INK);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        protected void onDraw(Canvas c){float d=getResources().getDisplayMetrics().density;for(int i=-1;i<=1;i++)c.drawCircle(getWidth()/2f+i*6*d,getHeight()/2f,1.7f*d,paint);}
    }
}
