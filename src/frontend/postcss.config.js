/**
 * PostCSS 配置
 *
 * postcss-nested：支持 CSS 嵌套语法（& 父选择器引用）
 * 在 Vue scoped 样式中，postcss-nested 会先把嵌套展开为平铺选择器，
 * 然后 Vue SFC 编译器再处理 :deep() 和 [data-v-xxx] 属性，
 * 确保 scoped 属性附加在正确的位置。
 *
 * 执行顺序：postcss-nested（展开嵌套） → vue scoped（附加属性）
 */
export default {
  plugins: {
    'postcss-nested': {}
  }
}
