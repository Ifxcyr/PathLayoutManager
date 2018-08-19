package com.wuyr.pathlayoutmanager;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Path;
import android.graphics.PointF;
import android.support.annotation.FloatRange;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;

import com.wuyr.pathlayoutmanager.keyframes.Keyframes;
import com.wuyr.pathlayoutmanager.keyframes.PosTan;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;


/**
 * Created by wuyr on 18-5-21 下午11:38.
 * GitHub: https://github.com/wuyr/PathLayoutManager
 */
public class PathLayoutManager extends RecyclerView.LayoutManager implements RecyclerView.SmoothScroller.ScrollVectorProvider {

    @IntDef({SCROLL_MODE_NORMAL, SCROLL_MODE_OVERFLOW, SCROLL_MODE_LOOP})
    @Retention(RetentionPolicy.SOURCE)
    private @interface ScrollMode {
    }

    /**
     * 普通模式
     */
    public static final int SCROLL_MODE_NORMAL = 0;

    /**
     * 溢出模式
     */
    public static final int SCROLL_MODE_OVERFLOW = 1;

    /**
     * 无限循环模式
     */
    public static final int SCROLL_MODE_LOOP = 2;

    private Keyframes mKeyframes; //关键帧
    private int mScrollMode; //滚动模式
    private int mOrientation; //滑动方向
    private int mItemOffset; //Item间距
    private int mItemCountInScreen; //屏幕中最多能同时显示的Item个数
    private int mFirstVisibleItemPos; //第一个可见的Item索引
    private float mOffsetX, mOffsetY; //x轴偏移量和y轴偏移量
    private boolean isItemDirectionFixed; //Item是否保持垂直
    private boolean isAutoSelect; //是否自动选中
    private float mAutoSelectFraction; //自动选中的落点 (0~1)
    private float[] mScaleRatio; //缩放比例
    private long mFixingAnimationDuration; //自动选中的动画时长
    private boolean isAnimatorInitialized;
    private int mCacheCount; //缓存的Item个数
    private boolean isFlingEnable; //是否开启惯性滚动
    private boolean isFlinging; //正在惯性滚动中

    //RecyclerView default ItemAnimator has bug on PathLayoutManager
    private RepairedItemAnimator mItemAnimator;
    private RecyclerView.Recycler mRecycler;
    private RecyclerView.State mState;
    private ValueAnimator mAnimator;

    private OnItemSelectedListener mItemSelectedListener;

    /**
     * @param path       目标路径
     * @param itemOffset Item间距
     */
    public PathLayoutManager(Path path, int itemOffset) {
        this(path, itemOffset, RecyclerView.VERTICAL);
    }

    /**
     * @param path        目标路径
     * @param itemOffset  Item间距
     * @param orientation 滑动方向
     */
    public PathLayoutManager(Path path, int itemOffset, @RecyclerView.Orientation int orientation) {
        mCacheCount = 10;
        mAutoSelectFraction = .5F;
        mFixingAnimationDuration = 250;
        mOrientation = orientation;
        mItemOffset = itemOffset;
        isFlingEnable = true;
        updatePath(path);
        mItemAnimator = new RepairedItemAnimator();
        mItemAnimator.setOnErrorListener(new RepairedItemAnimator.OnErrorListener() {
            @Override
            public void onError(RecyclerView.ViewHolder holder) {
                if (mRecycler != null && mState != null) {
                    removeAndRecycleAllViews(mRecycler);
                    for (int i = 0; i < mState.getItemCount(); i++) {
                        View view = mRecycler.getViewForPosition(i);
                        mRecycler.recycleView(view);
                    }
                    requestLayout();
                }
            }
        });
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        if (state.getItemCount() == 0) {
            removeAndRecycleAllViews(recycler);
            return;
        }

        mRecycler = recycler;
        mState = state;

        if (!isAnimatorInitialized) {
            initItemAnimator();
            isAnimatorInitialized = true;
        }

        detachAndScrapAttachedViews(recycler);
        relayoutChildren(recycler, state);
    }

    /**
     * 通过反射替换默认的Item动画 (解决在某些机型上的crash问题)
     */
    private void initItemAnimator() {
        try {
            Field field = RecyclerView.LayoutManager.class.getDeclaredField("mRecyclerView");
            field.setAccessible(true);
            RecyclerView recyclerView = (RecyclerView) field.get(this);
            if (recyclerView != null) {
                recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
                recyclerView.setHorizontalScrollBarEnabled(false);
                recyclerView.setVerticalScrollBarEnabled(false);
                if (recyclerView.getItemAnimator() != mItemAnimator) {
                    recyclerView.setItemAnimator(mItemAnimator);
                }
            }
//            recyclerView.setItemAnimator(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 检查状态并进行布局和回收旧Item
     */
    private void relayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        List<PosTan> needLayoutItems = getNeedLayoutItems();
        if (needLayoutItems.isEmpty() || state.getItemCount() == 0 || mKeyframes == null) {
            removeAndRecycleAllViews(recycler);
            return;
        }
        onLayout(recycler, needLayoutItems);
        recycleChildren(recycler, state, needLayoutItems);
    }

    /**
     * 确定Item位置，角度以及尺寸
     *
     * @param needLayoutItems 需要布局的Item
     */
    private void onLayout(RecyclerView.Recycler recycler, List<PosTan> needLayoutItems) {
        int x, y;
        View item;
        for (PosTan tmp : needLayoutItems) {
            item = recycler.getViewForPosition(tmp.index);
            addView(item);
            measureChild(item, 0, 0);

            //Path线条在View的中间
            x = (int) tmp.x - getDecoratedMeasuredWidth(item) / 2;
            y = (int) tmp.y - getDecoratedMeasuredHeight(item) / 2;

            layoutDecorated(item, x, y, x + getDecoratedMeasuredWidth(item), y + getDecoratedMeasuredHeight(item));
            item.setRotation(isItemDirectionFixed ? 0 : tmp.getChildAngle());
            if (mScaleRatio != null) {
                float scale = getScale(tmp.fraction);
                item.setScaleX(scale);
                item.setScaleY(scale);
            }
        }
    }

    /**
     * 根据Item在Path上的位置来获取对应的缩放比例
     *
     * @param fraction Item位置相对于Path总长度的百分比
     * @return 该Item的缩放比例
     */
    private float getScale(float fraction) {
        boolean isHasMin = false;
        boolean isHasMax = false;
        float minScale = 0;
        float maxScale = 0;
        float scalePosition;
        float minFraction = 1, maxFraction = 1;
        //必须从小到大遍历，才能找到最贴近fraction的scale
        for (int i = 1; i < mScaleRatio.length; i += 2) {
            scalePosition = mScaleRatio[i];
            if (scalePosition <= fraction) {
                minScale = mScaleRatio[i - 1];
                minFraction = mScaleRatio[i];
                isHasMin = true;
            } else {
                break;
            }
        }
        //必须从大到小遍历，才能找到最贴近fraction的scale
        for (int i = mScaleRatio.length - 1; i >= 1; i -= 2) {
            scalePosition = mScaleRatio[i];
            if (scalePosition >= fraction) {
                maxScale = mScaleRatio[i - 1];
                maxFraction = mScaleRatio[i];
                isHasMax = true;
            } else {
                break;
            }
        }
        if (!isHasMin) {
            minScale = 1;
        }
        if (!isHasMax) {
            maxScale = 1;
        }
        fraction = solveTwoPointForm(minFraction, maxFraction, fraction);
        float distance = maxScale - minScale;
        float scale = distance * fraction;
        float result = minScale + scale;
        return isFinite(result) ? result : minScale;
    }

    /**
     * 将基于总长度的百分比转换成基于某个片段的百分比 (解两点式直线方程)
     *
     * @param startX   片段起始百分比
     * @param endX     片段结束百分比
     * @param currentX 总长度百分比
     * @return 该片段的百分比
     */
    private float solveTwoPointForm(float startX, float endX, float currentX) {
        return (currentX - startX) / (endX - startX);
    }

    /**
     * 判断数值是否合法
     *
     * @param value 要判断的数值
     * @return 合法为true，反之
     */
    private boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private List<PosTan> getNeedLayoutItems() {
        checkKeyframes();
        List<PosTan> result = new ArrayList<>();
        //item个数
        int itemCount = getItemCount();
        //满足无限滚动
        if (isSatisfiedLoopScroll()) {
            initNeedLayoutLoopScrollItems(result, itemCount);
        } else {
            initNeedLayoutItems(result, itemCount);
        }
        return result;
    }

    /**
     * 初始化需要布局的Item数据 （无限滚动模式）
     *
     * @param result    结果
     * @param itemCount Item总数
     */
    private void initNeedLayoutLoopScrollItems(List<PosTan> result, int itemCount) {
        int overflowCount = getOverflowCount();
        //得出第一个可见的item
        mFirstVisibleItemPos = overflowCount - mItemCountInScreen - 1;
        float currentDistance;
        float fraction;
        PosTan posTan;
        int pos;
        for (int i = mFirstVisibleItemPos; i < overflowCount; i++) {
            pos = i % itemCount;
            if (pos < 0) {
                if (pos == -itemCount) {
                    pos = 0;
                } else {
                    // [0,1,2,3,4,5,6,7,8,9]
                    // -9 --> 1   -8 --> 2
                    pos += itemCount;
                }
            }
            currentDistance = (i + itemCount) * mItemOffset - getScrollOffset();
            fraction = currentDistance / mKeyframes.getPathLength();
            posTan = mKeyframes.getValue(fraction);
            if (posTan == null) {
                continue;
            }
            result.add(new PosTan(posTan, pos, fraction));
        }
    }


    /**
     * 初始化需要布局的Item数据 （非无限滚动模式）
     *
     * @param result    结果
     * @param itemCount Item总数
     */
    private void initNeedLayoutItems(List<PosTan> result, int itemCount) {
        float currentDistance;
        for (int i = 0; i < itemCount; i++) {
            currentDistance = i * mItemOffset - getScrollOffset();
            if (currentDistance >= 0) {
                mFirstVisibleItemPos = i;
                break;
            }
        }

        int endIndex = mFirstVisibleItemPos + mItemCountInScreen;
        if (endIndex > mState.getItemCount()) {
            endIndex = mState.getItemCount();
        }
        float fraction;
        PosTan posTan;
        for (int i = mFirstVisibleItemPos; i < endIndex; i++) {
            currentDistance = i * mItemOffset - getScrollOffset();
            fraction = currentDistance / mKeyframes.getPathLength();
            posTan = mKeyframes.getValue(fraction);
            if (posTan == null) {
                continue;
            }
            result.add(new PosTan(posTan, i, fraction));
        }
    }

    /**
     * 获取溢出的Item个数
     */
    private int getOverflowCount() {
        //item总长度
        int itemLength = getItemLength();

        //path的长度
        int pathLength = mKeyframes.getPathLength();

        //第一个item较Path终点的偏移量，这个偏移量是以Path的终点为起点的，例如 现在一共有10个item：
        //                     0___1___2___3___4___5 现在的偏移量是>0的，直到：
        //                     5___6___7___8___9___0 时为0，这个时候继续向右边滚动的话，就会变成负数了
        int firstItemScrollOffset = (int) (getScrollOffset() + pathLength);

        //同上，区别就是上面的是第一个item，这个是最后一个item，例如 现在一共有10个item：
        //                     0___1___2___3___4___5 现在的偏移量是<0的，一直到：
        //                     4___5___6___7___8___9 时为0
        //这样做就是为了：当最后一个item离开它应在的位置时 (常规的滑动模式最后一个item是坐死在最后的位置的)，
        //能够及时知道，并开始计算出它下一个item索引来补上它的空位
        int lastItemScrollOffset = firstItemScrollOffset - itemLength;
        //item的总长度 + path的总长度
        int lengthOffset = itemLength + pathLength;

        //当最后一个item滑出屏幕时(根据上面的例子来讲，是向左边滑)：
        //                     9_|_0___1___2___3___4
        // 开始计算的偏移量（正数），因为如果超出了屏幕而不作处理的话，
        // 下面计算空缺距离的时候，最大值只能是itemLength
        int lastItemOverflowOffset = firstItemScrollOffset > lengthOffset ?
                firstItemScrollOffset - lengthOffset : 0;

        //空缺的距离
        int vacantDistance = lastItemScrollOffset % itemLength + lastItemOverflowOffset;

        //空缺的距离 / item之间的距离 = 需补上的item个数
        return vacantDistance / mItemOffset;
    }

    /**
     * 回收屏幕外需回收的Item
     */
    private void recycleChildren(RecyclerView.Recycler recycler, RecyclerView.State state, List<PosTan> needLayoutDataList) {
        int itemCount = getItemCount();
        int firstIndex = needLayoutDataList.get(0).index;
        int lastIndex = needLayoutDataList.get(needLayoutDataList.size() - 1).index;
        int forwardStartIndex, forwardEndIndex;
        int backwardStartIndex, backwardEndIndex;
        boolean needRecyclerForward = false, needRecyclerBackward = false;

        //要保留设置的缓存个数
        forwardEndIndex = firstIndex - mCacheCount / 2;
        backwardStartIndex = lastIndex + mCacheCount / 2;
        forwardStartIndex = forwardEndIndex - mCacheCount;
        backwardEndIndex = backwardStartIndex + mCacheCount;

        if (isSatisfiedLoopScroll()) {
            if (itemCount > mCacheCount + mItemCountInScreen) {
                //因为是循环滚动(没有边界)所以总是需要回收
                needRecyclerForward = true;
                needRecyclerBackward = true;
                for (int i = forwardStartIndex; i < backwardEndIndex; i++) {
                    //检测两边回收的是否有碰撞，如有，将两边的端点各后退一步
                    for (int j = backwardStartIndex; j < backwardEndIndex; j++) {
                        if (fixOverflowIndex(j, itemCount) == fixOverflowIndex(i, itemCount)) {
                            forwardStartIndex++;
                            backwardEndIndex--;
                        }
                    }
                }
            }
        } else {
            if (forwardEndIndex > 0) {
                if (forwardStartIndex < 0) {
                    forwardStartIndex = 0;
                }
                needRecyclerForward = true;
            }
            if (backwardStartIndex < itemCount - 1) {
                if (backwardEndIndex >= itemCount) {
                    backwardEndIndex = itemCount - 1;
                }
                needRecyclerBackward = true;
            }
        }
        if (needRecyclerForward) {
            recycleChildren(recycler, state, forwardStartIndex, forwardEndIndex);
        }
        if (needRecyclerBackward) {
            recycleChildren(recycler, state, backwardStartIndex, backwardEndIndex);
        }
    }

    /**
     * 回收屏幕外需回收的Item
     */
    private void recycleChildren(RecyclerView.Recycler recycler, RecyclerView.State state, int startIndex, int endIndex) {
        int temp;
        for (int i = startIndex; i <= endIndex; i++) {
            temp = isSatisfiedLoopScroll() ? fixOverflowIndex(i, getItemCount()) : i;
            if (temp >= state.getItemCount()) {
                break;
            }
            final View view = recycler.getViewForPosition(temp);
            if (view != null) {
                recycler.recycleView(view);
            }
        }
    }

    /**
     * 把小于0或者大于getItemCount()的索引转换成合法的索引
     * 比如: getItemCount() = 10
     * 如果此时index传 11 那么就返回 1
     * 如果index为 -1 则返回 10
     */
    private int fixOverflowIndex(int index, int count) {
        while (index < 0) {
            index += count;
        }
        return index % count;
    }

    @Override
    public int scrollHorizontallyBy(int dx, RecyclerView.Recycler recycler, RecyclerView.State state) {
        mRecycler = recycler;
        mState = state;
        checkKeyframes();
        detachAndScrapAttachedViews(recycler);
        //临时记录上一次的offset
        float lastOffset = mOffsetX;
        updateOffsetX(dx);
        relayoutChildren(recycler, state);
        //如果offset没有改变，那么就直接return 0了
        return lastOffset == mOffsetX ? 0 : dx;
    }

    @Override
    public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler, RecyclerView.State state) {
        mRecycler = recycler;
        mState = state;
        checkKeyframes();
        detachAndScrapAttachedViews(recycler);
        float lastOffset = mOffsetY;
        updateOffsetY(dy);
        relayoutChildren(recycler, state);
        //如果offset没有改变，那么就直接return 0了
        return lastOffset == mOffsetY ? 0 : dy;
    }

    /**
     * 更新Y轴偏移量
     *
     * @param offsetY 偏移量
     */
    private void updateOffsetY(float offsetY) {
        if (isFlinging && !isFlingEnable) {
            return;
        }
        mOffsetY += offsetY;
        int pathLength = mKeyframes.getPathLength();
        int itemLength = getItemLength();
        if ((isSatisfiedLoopScroll(pathLength, itemLength))) {
            if (mOffsetY > itemLength) {
                mOffsetY %= itemLength;
                //因为是向前偏移了一个Item的距离
                mOffsetY -= mItemOffset;
            } else if (mOffsetY <= -pathLength) {
                mOffsetY += itemLength;
                mOffsetY += mItemOffset;
            }
        } else {
            if (isOverflowMode()) {
                if (mOffsetY < -pathLength) {
                    mOffsetY = -pathLength;
                } else if (mOffsetY > itemLength) {
                    mOffsetY = itemLength;
                }
            } else {
                int overflowLength = itemLength - pathLength;
                if (mOffsetY < 0) {
                    mOffsetY = 0;
                } else if (mOffsetY > overflowLength) {
                    if (itemLength > pathLength) {
                        mOffsetY = overflowLength;
                    } else {
                        mOffsetY -= offsetY;
                    }
                }
            }
        }
    }

    /**
     * 更新X轴偏移量
     *
     * @param offsetX 偏移量
     */
    private void updateOffsetX(float offsetX) {
        if (isFlinging && !isFlingEnable) {
            return;
        }
        mOffsetX += offsetX;
        int pathLength = mKeyframes.getPathLength();
        int itemLength = getItemLength();
        if (isSatisfiedLoopScroll(pathLength, itemLength)) {
            if (mOffsetX > itemLength) {
                mOffsetX %= itemLength;
                //因为是向前偏移了一个Item的距离
                mOffsetX -= mItemOffset;
            } else if (mOffsetX <= -pathLength) {
                mOffsetX += itemLength;
                mOffsetX += mItemOffset;
            }
        } else {
            if (isOverflowMode()) {
                if (mOffsetX < -pathLength) {
                    mOffsetX = -pathLength;
                } else if (mOffsetX > itemLength) {
                    mOffsetX = itemLength;
                }
            } else {
                int overflowLength = itemLength - pathLength;
                if (mOffsetX < 0) {
                    mOffsetX = 0;
                } else if (mOffsetX > overflowLength) {
                    if (itemLength > pathLength) {
                        mOffsetX = overflowLength;
                    } else {
                        mOffsetX -= offsetX;
                    }
                }
            }
        }
    }

    /**
     * 判断是否满足无限循环滚动条件
     * 条件： 必须明确开始无限循环模式，并且Item的总长度要大声Path的总长度
     */
    private boolean isSatisfiedLoopScroll() {
        checkKeyframes();
        int pathLength = mKeyframes.getPathLength();
        int itemLength = getItemLength();
        return isLoopScrollMode() && itemLength - pathLength > mItemOffset;
    }


    /**
     * 判断是否满足无限循环滚动条件
     * 条件： 必须明确设置滚动模式为 无限循环模式，并且Item的总长度要大声Path的总长度
     */
    private boolean isSatisfiedLoopScroll(int pathLength, int itemLength) {
        return isLoopScrollMode() && itemLength - pathLength > mItemOffset;
    }

    /**
     * @return 滚动模式是否为 无限循环滚动模式
     */
    private boolean isLoopScrollMode() {
        return mScrollMode == SCROLL_MODE_LOOP;
    }

    /**
     * @return 滚动模式是否为 溢出模式
     */
    private boolean isOverflowMode() {
        return mScrollMode == SCROLL_MODE_OVERFLOW;
    }

    /**
     * @return Item总长度
     */
    private int getItemLength() {
        //这里 +1 是为了让最后一个item 显示出来 (让最后一个item的距离相对于Path长度的百分比<1，
        // 即使其满足mKeyframes.getValue()方法里面获取有效坐标点的条件)
        return getItemCount() * mItemOffset - mItemOffset + 1;
    }

    /**
     * 根据当前设置的滚动方向来获取对应的滚动偏移量
     */
    private float getScrollOffset() {
        return mOrientation == RecyclerView.VERTICAL ? mOffsetY : mOffsetX;
    }

    @Override
    public boolean canScrollVertically() {
        return mOrientation == RecyclerView.VERTICAL;
    }

    @Override
    public boolean canScrollHorizontally() {
        return mOrientation == RecyclerView.HORIZONTAL;
    }

    /**
     * 更新Path
     */
    public void updatePath(Path path) {
        if (path != null) {
            mKeyframes = new Keyframes(path);
            if (mItemOffset == 0) {
                throw new IllegalStateException("itemOffset must be > 0 !!!");
            }
            //这里 +1 是为了多显示一个item
            // 调整前：       ___0___1___2___3___4___5

            // 调整后(不+1):  0___1___2___3___4___5___

            // 调整后(+1):    0___1___2___3___4___5___6
            mItemCountInScreen = mKeyframes.getPathLength() / mItemOffset + 1;
        }
        requestLayout();
    }

    /**
     * 设置Item间距
     *
     * @param itemOffset Item间距
     */
    public void setItemOffset(int itemOffset) {
        if (mItemOffset != itemOffset && itemOffset > 0) {
            mItemOffset = itemOffset;
//            mItemOffset改变后要刷新mItemCountInScreen
            if (mKeyframes != null) {
                mItemCountInScreen = mKeyframes.getPathLength() / mItemOffset + 1;
                requestLayout();
            }
        }
    }

    /**
     * 设置Item是否保持垂直
     */
    public void setItemDirectionFixed(boolean isFixed) {
        if (isItemDirectionFixed != isFixed) {
            isItemDirectionFixed = isFixed;
            requestLayout();
        }
    }

    /**
     * 设置滚动模式
     *
     * @param mode {@link #SCROLL_MODE_NORMAL}
     *             {@link #SCROLL_MODE_OVERFLOW}
     *             {@link #SCROLL_MODE_LOOP}
     */
    public void setScrollMode(@ScrollMode int mode) {
        if (mode != mScrollMode) {
            mScrollMode = mode;
            requestLayout();
        }
    }

    /**
     * 设置滑动方向
     *
     * @param orientation {@see RecyclerView.HORIZONTAL}
     *                    {@see RecyclerView.VERTICAL}
     */
    @SuppressWarnings("SuspiciousNameCombination")
    public void setOrientation(@RecyclerView.Orientation int orientation) {
        if (mOrientation != orientation) {
            mOrientation = orientation;
            if (orientation == RecyclerView.HORIZONTAL) {
                mOffsetX = mOffsetY;
                mOffsetY = 0;
            } else {
                mOffsetY = mOffsetX;
                mOffsetX = 0;
            }
        }
    }

    /**
     * 设置缓存个数
     */
    public void setCacheCount(int count) {
        mCacheCount = count;
    }

    @Override
    public void onScrollStateChanged(int state) {
        isFlinging = state == RecyclerView.SCROLL_STATE_SETTLING;
        switch (state) {
            case RecyclerView.SCROLL_STATE_DRAGGING:
                stopFixingAnimation();
                break;
            case RecyclerView.SCROLL_STATE_IDLE:
                if (isAutoSelect) {
                    smoothScrollToPosition(findClosestPosition());
                }
                break;
            default:
                break;
        }
    }

    /**
     * 停止动画
     */
    private void stopFixingAnimation() {
        if (mAnimator != null && mAnimator.isRunning()) {
            mAnimator.cancel();
        }
    }

    @Override
    public void scrollToPosition(int position) {
        int itemCount = getItemCount();
        if (position > -1 && position < itemCount) {
            checkKeyframes();
            int distance = getDistance(position);
            if (canScrollVertically()) {
                updateOffsetY(distance);
            } else {
                updateOffsetX(distance);
            }
            requestLayout();
        }
    }

    /**
     * 根据传入的position来获取离目标落点的最近距离
     */
    private int getDistance(int position) {
        PosTan posTan = getVisiblePosTanByPosition(position);
        float distance;
        //如果这个item不可见
        if (posTan == null) {
            int itemCount = getItemCount();
            int closestPosition = findClosestPosition();
            int count = 0;
            do {
                count++;
            } while (fixOverflowIndex(closestPosition + count, itemCount) != position);
            //如果设置了无限滚动的话，判断哪一边更接近来决定是向前滚动还是向后滚动
            if (isSatisfiedLoopScroll() &&
                    count < Math.abs(closestPosition - position)) {
                position = closestPosition + count;
            }
            //计算选中position与Path起点之间的距离。
            distance = position * mItemOffset - getScrollOffset();
            //再加上自动选中落点的这段距离(因为我们看到的向下滑动，其实是显示的区域向上移动了，所以这里是减)
        } else {
            //如果屏幕中存在这个item的话，直接偏移屏幕中的
            distance = mKeyframes.getPathLength() * posTan.fraction;
        }
        //定位到设定的落点位置
        distance -= mKeyframes.getPathLength() * mAutoSelectFraction;
        return (int) distance;
    }

    @Nullable
    private PosTan getVisiblePosTanByPosition(int position) {
        List<PosTan> needLayoutList = getNeedLayoutItems();
        PosTan posTan = null;
        for (int i = 0; i < needLayoutList.size(); i++) {
            PosTan tmp = needLayoutList.get(i);
            if (tmp.index == position) {
                posTan = tmp;
                break;
            }
        }
        return posTan;
    }

    @Override
    public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state, int position) {
        smoothScrollToPosition(position);
    }

    /**
     * 平滑滚动到某个位置
     *
     * @param position 目标Item索引
     */
    public void smoothScrollToPosition(int position) {
        if (position > -1 && position < getItemCount()) {
            checkKeyframes();
            startValueAnimator(position);
        }
    }

    /**
     * 播放平滑滚动动画并更新偏移量
     *
     * @param position 目标Item索引
     */
    private void startValueAnimator(final int position) {
        //如果上一次的动画未播放完，就先取消它
        stopFixingAnimation();

        int distance = getDistance(position);

        mAnimator = ValueAnimator.ofFloat(0, distance).setDuration(mFixingAnimationDuration);
        mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {

            private float mLastScrollOffset;

            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float currentValue = (float) animation.getAnimatedValue();
                if (mLastScrollOffset != 0) {
                    float offset = currentValue - mLastScrollOffset;
                    if (canScrollVertically()) {
                        updateOffsetY(offset);
                    } else {
                        updateOffsetX(offset);
                    }
                    requestLayout();
                }
                mLastScrollOffset = currentValue;
            }
        });
        mAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (isAutoSelect) {
                    if (mItemSelectedListener != null) {
                        mItemSelectedListener.onSelected(position);
                    }
                }
            }
        });
        mAnimator.start();
    }

    /**
     * 找出离目标落点最近的Item索引
     */
    private int findClosestPosition() {
        int hitPos = -1;
        List<PosTan> posTanList = getNeedLayoutItems();
        if (posTanList.size() > 1) {
            hitPos = posTanList.get(0).index;
            float hitFraction = Math.abs(posTanList.get(0).fraction - mAutoSelectFraction);
            for (PosTan tmp : posTanList) {
                float tempFraction = Math.abs(tmp.fraction - mAutoSelectFraction);
                //跟现在认为最近的距离做比较，取更近的那一方
                if (tempFraction < hitFraction) {
                    hitPos = tmp.index;
                    hitFraction = tempFraction;
                }
            }
        }
        if (hitPos < 0) {
            if (!posTanList.isEmpty()) {
                hitPos = posTanList.get(0).index;
            }
        }
        return hitPos;
    }

    /**
     * 设置自动选中的目标落点
     *
     * @param position 目标落点 (0~1)
     */
    public void setAutoSelectFraction(@FloatRange(from = 0F, to = 1F) float position) {
        if (mAutoSelectFraction != position) {
            mAutoSelectFraction = position;
            requestLayout();
        }
    }

    /**
     * 设置是否开启自动选中效果
     */
    public void setAutoSelect(boolean isAutoSelect) {
        if (this.isAutoSelect != isAutoSelect) {
            this.isAutoSelect = isAutoSelect;
            if (isAutoSelect && mKeyframes != null) {
                onScrollStateChanged(RecyclerView.SCROLL_STATE_IDLE);
            }
        }
    }

    /**
     * 设置平滑缩放比例
     *
     * @param ratios 缩放比例， 数组长度必须是双数，
     *               偶数索引表示要缩放的比例，
     *               奇数索引表示在路径上的位置(0~1)
     *               奇数索引必须要递增，即越往后的数值应越大
     *               例如： setItemScaleRatio(0.8, 0.5) 即表示在路径的50%处把Item缩放到原来的80%
     *               setItemScaleRatio(0, 0, 1, 0.5, 0, 1) 即表示在路径的起点和终点处，皆把Item缩放至原来的0%，
     *               而在50%处把Item恢复原样
     */
    public void setItemScaleRatio(float... ratios) {
        if (ratios.length == 0) {
            ratios = new float[]{1, 1};
        }
        for (float tmp : ratios) {
            if (tmp < 0) {
                throw new IllegalArgumentException("Array value can not be negative!");
            }
        }
        if (mScaleRatio != ratios) {
            if (ratios.length < 2 || ratios.length % 2 != 0) {
                throw new IllegalArgumentException("Array length no match!");
            }
            mScaleRatio = ratios;
            if (mScaleRatio[1] != 0) {
                mScaleRatio = insertElement(true, mScaleRatio, 1F, 0F);
            }
            if (mScaleRatio[mScaleRatio.length - 1] != 1) {
                mScaleRatio = insertElement(false, mScaleRatio, 1F, 1F);
            }
            float min = mScaleRatio[1];
            float temp;
            for (int i = 1; i < mScaleRatio.length; i += 2) {
                temp = mScaleRatio[i];
                if (min > temp) {
                    throw new IllegalArgumentException("Incorrect array value! position must be from small to large");
                } else {
                    min = temp;
                }
            }
            requestLayout();
        }
    }

    /**
     * 扩展数组元素
     *
     * @param isAddFromHead 是否从头部添加
     * @param target        目标数组
     * @param elements      需要插入的数值
     * @return 扩展后的数组
     */
    private float[] insertElement(boolean isAddFromHead, @NonNull float[] target, @NonNull float... elements) {
        float[] result = new float[target.length + elements.length];
        if (isAddFromHead) {
            System.arraycopy(elements, 0, result, 0, elements.length);
            System.arraycopy(target, 0, result, elements.length, target.length);
        } else {
            System.arraycopy(target, 0, result, 0, target.length);
            System.arraycopy(elements, 0, result, target.length, elements.length);
        }
        return result;
    }

    /**
     * 设置自动选中后的选中动画时长
     *
     * @param duration 动画时长
     */
    public void setFixingAnimationDuration(long duration) {
        mFixingAnimationDuration = duration;
    }

    /**
     * 设置惯性滚动是否开启
     */
    public void setFlingEnable(boolean enable) {
        isFlingEnable = enable;
    }

    /**
     * 设置Item选中后的监听器
     */
    public void setOnItemSelectedListener(OnItemSelectedListener listener) {
        mItemSelectedListener = listener;
    }

    private void checkKeyframes() {
        if (mKeyframes == null) {
            throw new NullPointerException("Path not set!");
        }
    }

    @Override
    public void onDetachedFromWindow(RecyclerView view, RecyclerView.Recycler recycler) {
        if (mKeyframes != null) {
            mKeyframes.release();
            mKeyframes = null;
        }
        mScaleRatio = null;
        mItemAnimator = null;
        mRecycler = null;
        mState = null;
        stopFixingAnimation();
        mAnimator = null;
    }

    @Override
    public RecyclerView.LayoutParams generateDefaultLayoutParams() {
        return new RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    public PointF computeScrollVectorForPosition(int targetPosition) {
        return null;
    }

    @Override
    public void onItemsAdded(RecyclerView recyclerView, int positionStart, int itemCount) {
        delayNotifyDataSetChanged(recyclerView);
    }

    @Override
    public void onItemsRemoved(RecyclerView recyclerView, int positionStart, int itemCount) {
        delayNotifyDataSetChanged(recyclerView);
    }

    @Override
    public void onItemsUpdated(RecyclerView recyclerView, int positionStart, int itemCount) {
        delayNotifyDataSetChanged(recyclerView);
    }

    @Override
    public void onItemsUpdated(RecyclerView recyclerView, int positionStart, int itemCount, Object payload) {
        delayNotifyDataSetChanged(recyclerView);
    }

    @Override
    public void onItemsMoved(RecyclerView recyclerView, int from, int to, int itemCount) {
        delayNotifyDataSetChanged(recyclerView);
    }

    private volatile boolean isPosting;

    private void delayNotifyDataSetChanged(final RecyclerView recyclerView) {
        if (recyclerView.isComputingLayout()) {
            isPosting = true;
            recyclerView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    delayNotifyDataSetChanged(recyclerView);
                }
            }, 5);
        } else {
            if (isPosting) {
                isPosting = false;
                recyclerView.getAdapter().notifyDataSetChanged();
            }
        }
    }

    public interface OnItemSelectedListener {
        void onSelected(int position);
    }
}