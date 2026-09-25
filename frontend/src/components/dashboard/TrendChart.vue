<script setup lang="ts">
import { computed } from 'vue'
import { use, type ComposeOption } from 'echarts/core'
import { LineChart, type LineSeriesOption } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  AriaComponent,
  type GridComponentOption,
  type TooltipComponentOption,
  type LegendComponentOption,
} from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'

use([LineChart, GridComponent, TooltipComponent, LegendComponent, AriaComponent, SVGRenderer])
type Option = ComposeOption<
  LineSeriesOption | GridComponentOption | TooltipComponentOption | LegendComponentOption
>
const props = defineProps<{
  dates: string[]
  series: { name: string; values: (number | null)[]; color: string }[]
  unit: string
  label: string
  samples?: number[]
}>()
const option = computed<Option>(() => ({
  animation: false,
  aria: { enabled: true, description: props.label },
  grid: { left: 48, right: 18, top: 24, bottom: props.series.length > 1 ? 62 : 30 },
  tooltip: {
    trigger: 'axis',
    confine: true,
    axisPointer: { type: 'line', lineStyle: { color: '#cbd5e1', type: 'dashed' } },
    valueFormatter: (value, index) =>
      value == null || value === '-'
        ? '无样本'
        : `${value} ${props.unit}${props.samples && index != null ? ` · ${props.samples[index]} 个样本` : ''}`,
  },
  legend: {
    show: props.series.length > 1,
    bottom: 0,
    icon: 'roundRect',
    itemWidth: 16,
    itemHeight: 3,
  },
  xAxis: {
    type: 'category',
    data: props.dates,
    boundaryGap: false,
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: {
      color: '#64748b',
      fontSize: 11,
      formatter: (value: string) => value.slice(5).replace('-', '/'),
    },
  },
  yAxis: {
    type: 'value',
    min: 0,
    minInterval: props.unit === '次' ? 1 : undefined,
    name: props.unit,
    nameTextStyle: { color: '#64748b', padding: [0, 20, 0, 0] },
    axisLabel: { color: '#64748b', fontSize: 11 },
    splitLine: { lineStyle: { color: '#edf0f4', type: 'dashed' } },
  },
  series: props.series.map((series, index) => ({
    name: series.name,
    type: 'line',
    data: series.values,
    smooth: false,
    connectNulls: false,
    showSymbol:
      series.values.filter((value) => value != null).length === 1 ||
      series.values.some((value) => value == null),
    symbolSize: 7,
    lineStyle: { width: 2, type: index === 1 ? 'dashed' : 'solid' },
    itemStyle: { color: series.color },
    areaStyle:
      props.series.length === 1
        ? {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: `${series.color}24` },
                { offset: 1, color: `${series.color}00` },
              ],
            },
          }
        : undefined,
    emphasis: { focus: 'series' },
  })),
}))
</script>

<template>
  <VChart
    class="trend-chart"
    :option="option"
    :init-options="{ renderer: 'svg' }"
    autoresize
    :aria-label="label"
  />
</template>

<style scoped>
.trend-chart {
  width: 100%;
  height: 240px;
  min-width: 0;
}
</style>
