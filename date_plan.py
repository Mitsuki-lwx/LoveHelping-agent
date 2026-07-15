
# -*- coding: utf-8 -*-
import os
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import cm, mm
from reportlab.lib.colors import HexColor, white, black
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (SimpleDocTemplate, Paragraph, Spacer, Image,
                                  Table, TableStyle, PageBreak)
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.enums import TA_CENTER, TA_LEFT, TA_JUSTIFY
from reportlab.lib import colors
import urllib.request

# Register Chinese font
pdfmetrics.registerFont(TTFont('SimHei', 'C:/Windows/Fonts/simhei.ttf'))
pdfmetrics.registerFont(TTFont('MSYH', 'C:/Windows/Fonts/msyh.ttc'))

# Color scheme - Romantic pink/purple theme
PINK = HexColor('#E91E63')
LIGHT_PINK = HexColor('#FCE4EC')
PURPLE = HexColor('#7B1FA2')
LIGHT_PURPLE = HexColor('#F3E5F5')
DARK_TEXT = HexColor('#333333')
ACCENT_GOLD = HexColor('#FFD700')
SOFT_BG = HexColor('#FFF0F5')

# Create document
doc = SimpleDocTemplate("date_plan.pdf", pagesize=A4,
                        rightMargin=2*cm, leftMargin=2*cm,
                        topMargin=1.5*cm, bottomMargin=1.5*cm)

styles = getSampleStyleSheet()

# Custom styles
title_style = ParagraphStyle('CustomTitle', parent=styles['Title'],
    fontName='SimHei', fontSize=28, textColor=PINK, alignment=TA_CENTER,
    spaceAfter=10, leading=36)

subtitle_style = ParagraphStyle('Subtitle', parent=styles['Normal'],
    fontName='SimHei', fontSize=14, textColor=PURPLE, alignment=TA_CENTER,
    spaceAfter=20, leading=20)

heading_style = ParagraphStyle('Heading', parent=styles['Heading1'],
    fontName='SimHei', fontSize=18, textColor=PINK, spaceBefore=20,
    spaceAfter=10, leading=24, borderWidth=0, borderPadding=0)

sub_heading_style = ParagraphStyle('SubHeading', parent=styles['Heading2'],
    fontName='SimHei', fontSize=14, textColor=PURPLE, spaceBefore=12,
    spaceAfter=8, leading=18)

body_style = ParagraphStyle('Body', parent=styles['Normal'],
    fontName='SimHei', fontSize=11, textColor=DARK_TEXT, alignment=TA_JUSTIFY,
    spaceAfter=8, leading=18)

time_style = ParagraphStyle('Time', parent=styles['Normal'],
    fontName='SimHei', fontSize=12, textColor=white, alignment=TA_CENTER,
    leading=16)

tip_style = ParagraphStyle('Tip', parent=styles['Normal'],
    fontName='SimHei', fontSize=10, textColor=PURPLE, alignment=TA_LEFT,
    spaceAfter=6, leading=16, leftIndent=10)

info_style = ParagraphStyle('Info', parent=styles['Normal'],
    fontName='SimHei', fontSize=10, textColor=DARK_TEXT, alignment=TA_LEFT,
    spaceAfter=4, leading=16)

story = []

# ========== COVER PAGE ==========
story.append(Spacer(1, 3*cm))

# Decorative heart symbol
heart_data = [[Paragraph('<font size="60" color="#E91E63">♥</font>', body_style)]]
heart_table = Table(heart_data, colWidths=[16*cm])
heart_table.setStyle(TableStyle([('ALIGN', (0,0), (-1,-1), 'CENTER')]))
story.append(heart_table)
story.append(Spacer(1, 1*cm))

story.append(Paragraph('佛山南海浪漫约会计划', title_style))
story.append(Spacer(1, 0.3*cm))
story.append(Paragraph('—— 为你和你心爱的TA精心策划 ——', subtitle_style))
story.append(Spacer(1, 1*cm))

# Cover info box
cover_info = [
    ['约会区域', '佛山市南海区（5公里范围内）'],
    ['约会时长', '全天（约12小时）'],
    ['约会主题', '浪漫 · 美食 · 景观 · 文化'],
    ['推荐季节', '四季皆宜，春秋最佳'],
    ['交通方式', '自驾 / 公共交通'],
]
cover_table = Table(cover_info, colWidths=[5*cm, 10*cm])
cover_table.setStyle(TableStyle([
    ('FONTNAME', (0,0), (-1,-1), 'SimHei'),
    ('FONTSIZE', (0,0), (-1,-1), 11),
    ('TEXTCOLOR', (0,0), (0,-1), white),
    ('TEXTCOLOR', (1,0), (1,-1), DARK_TEXT),
    ('BACKGROUND', (0,0), (0,-1), PINK),
    ('BACKGROUND', (1,0), (1,-1), LIGHT_PINK),
    ('ALIGN', (0,0), (-1,-1), 'CENTER'),
    ('VALIGN', (0,0), (-1,-1), 'MIDDLE'),
    ('TOPPADDING', (0,0), (-1,-1), 10),
    ('BOTTOMPADDING', (0,0), (-1,-1), 10),
    ('LEFTPADDING', (0,0), (-1,-1), 15),
    ('RIGHTPADDING', (0,0), (-1,-1), 15),
    ('ROUNDEDCORNERS', [6, 6, 6, 6]),
]))
story.append(cover_table)
story.append(Spacer(1, 1.5*cm))

# Quote
quote_text = '<para align="center"><font name="SimHei" size="11" color="#7B1FA2"><i>"陪伴是最长情的告白，美食是最浪漫的共享。<br/>和心中的TA，相聚南海，打造属于你们的浪漫回忆。"</i></font></para>'
story.append(Paragraph(quote_text, body_style))

story.append(PageBreak())

# ========== TABLE OF CONTENTS ==========
story.append(Paragraph('目 录', title_style))
story.append(Spacer(1, 0.5*cm))

toc_items = [
    ['一、', '约会地点概览'],
    ['二、', '上午时光 — 千灯湖晨间漫步'],
    ['三、', '午餐时光 — 1874音乐西餐'],
    ['四、', '下午茶歇 — 怡海港爱情大道'],
    ['五、', '傍晚漫步 — 保利西街'],
    ['六、', '晚餐时光 — 华希尔顿逸林酒店'],
    ['七、', '夜晚浪漫 — 万达广场摩天轮'],
    ['八、', '约会小贴士 & 备选方案'],
    ['九、', '交通与预算参考'],
]
toc_table = Table(toc_items, colWidths=[2*cm, 14*cm])
toc_table.setStyle(TableStyle([
    ('FONTNAME', (0,0), (-1,-1), 'SimHei'),
    ('FONTSIZE', (0,0), (-1,-1), 13),
    ('TEXTCOLOR', (0,0), (0,-1), PINK),
    ('TEXTCOLOR', (1,0), (1,-1), DARK_TEXT),
    ('ALIGN', (0,0), (0,-1), 'CENTER'),
    ('ALIGN', (1,0), (1,-1), 'LEFT'),
    ('TOPPADDING', (0,0), (-1,-1), 8),
    ('BOTTOMPADDING', (0,0), (-1,-1), 8),
    ('LINEBELOW', (0,0), (-1,-1), 0.5, LIGHT_PINK),
    ('LEFTPADDING', (1,0), (1,-1), 20),
]))
story.append(toc_table)

story.append(PageBreak())

# ========== SECTION 1: OVERVIEW ==========
story.append(Paragraph('一、约会地点概览', heading_style))
story.append(Spacer(1, 0.3*cm))
story.append(Paragraph('本次约会计划精选了佛山市南海区5公里范围内的6大浪漫地点，涵盖自然景观、美食体验、休闲娱乐和文化氛围，为您打造一场完美的全天约会体验。', body_style))
story.append(Spacer(1, 0.3*cm))

# Overview table
overview_data = [
    ['时间', '地点', '活动', '亮点'],
    ['09:00-11:00', '千灯湖公园', '晨间漫步', '湖光城色，清新自然'],
    ['11:30-13:30', '1874音乐西餐', '浪漫午餐', '音乐西餐，观景美食'],
    ['14:00-16:00', '怡海港爱情大道', '下午漫步', '爱情灯柱，同心锁'],
    ['16:30-18:00', '保利西街', '逛街休闲', '风情街区，咖啡花艺'],
    ['18:30-20:30', '希尔顿逸林酒店', '浪漫晚餐', '高空夜景，云端餐厅'],
    ['21:00-22:00', '万达广场摩天轮', '夜景浪漫', '城市灯火，星空许愿'],
]
overview_table = Table(overview_data, colWidths=[2.8*cm, 4*cm, 3.5*cm, 5.2*cm])
overview_table.setStyle(TableStyle([
    ('FONTNAME', (0,0), (-1,-1), 'SimHei'),
    ('FONTSIZE', (0,0), (-1,-1), 10),
    ('FONTSIZE', (0,0), (-1,0), 11),
    ('BACKGROUND', (0,0), (-1,0), PINK),
    ('TEXTCOLOR', (0,0), (-1,0), white),
    ('TEXTCOLOR', (0,1), (-1,-1), DARK_TEXT),
    ('ALIGN', (0,0), (-1,-1), 'CENTER'),
    ('VALIGN', (0,0), (-1,-1), 'MIDDLE'),
    ('TOPPADDING', (0,0), (-1,-1), 8),
    ('BOTTOMPADDING', (0,0), (-1,-1), 8),
    ('ROWBACKGROUNDS', (0,1), (-1,-1), [SOFT_BG, white]),
    ('GRID', (0,0), (-1,-1), 0.5, LIGHT_PINK),
    ('ROUNDEDCORNERS', [6, 6, 6, 6]),
]))
story.append(overview_table)

story.append(PageBreak())

# ========== SECTION 2: MORNING ==========
story.append(Paragraph('二、上午时光 — 千灯湖晨间漫步', heading_style))
story.append(Spacer(1, 0.3*cm))

# Time badge
time_badge_data = [[Paragraph('⏰ 09:00 - 11:00', time_style)]]
time_badge = Table(time_badge_data, colWidths=[4*cm])
time_badge.setStyle(TableStyle([
    ('BACKGROUND', (0,0), (-1,-1), PINK),
    ('ALIGN', (0,0), (-1,-1), 'CENTER'),
    ('TOPPADDING', (0,0), (-1,-1), 6),
    ('BOTTOMPADDING', (0,0), (-1,-1), 6),
    ('ROUNDEDCORNERS', [10, 10, 10, 10]),
]))
story.append(time_badge)
story.append(Spacer(1, 0.5*cm))

story.append(Paragraph('📍 地点：千灯湖公园（南海区桂城街道）', sub_heading_style))
story.append(Paragraph('千灯湖是佛山南海区的标志性城市公园，湖面碧波荡漾，绿树成荫。清晨时分，阳光洒在湖面上，金光闪闪，是情侣晨间散步的绝佳选择。沿着湖边的步道漫步，呼吸新鲜空气，享受二人世界的宁静时光。', body_style))
story.append(Spacer(1, 0.2*cm))

# Info cards
info_data = [
    ['地址', '佛山市南海区桂城街道千灯湖'],
    ['门票', '免费开放'],
    ['建议时长', '约2小时'],
    ['交通', '广佛线千灯湖站直达'],
    ['推荐活动', '湖边漫步、拍照打卡、晨间瑜伽'],
]
info_table = Table(info_data, colWidths=[3*cm, 12*cm])
info_table.setStyle(TableStyle([
    ('FONTNAME', (0,0), (-1,-1), 'SimHei'),
    ('FONTSIZE', (0,0), (-1,-1), 10),
    ('TEXTCOLOR', (0,0), (0,-1), white),
    ('TEXTCOLOR', (1,0), (1,-1), DARK_TEXT),
    ('BACKGROUND', (0,0), (0,-1), PURPLE),
    ('BACKGROUND', (1,0), (1,-1), LIGHT_PURPLE),
    ('ALIGN', (0,0), (0,-1), 'CENTER'),
    ('VALIGN', (0,0), (-1,-1), 'MIDDLE'),
    ('TOPPADDING', (0,0), (-1,-1), 6),
    ('BOTTOMPADDING', (0,0), (-1,-1), 6),
]))
story.append(info_table)
story.append(Spacer(1, 0.3*cm))

story.append(Paragraph('💡 小贴士：', sub_heading_style))
tips = [
    '• 建议早上8:30出发，避开人流高峰，享受更私密的约会时光',
    '• 湖边有多处网红拍照点，记得带上相机或手机为TA拍美照',
    '• 湖边有座椅可以小坐休息，带上保温杯的热饮会更贴心',
    '• 千灯湖音乐喷泉晚上也有表演，可以考虑晚上再来一次',
]
for tip in tips:
    story.append(Paragraph(tip, tip_style))

story.append(PageBreak())

# ========== SECTION 3: LUNCH ==========
story.append(Paragraph('三、午餐时光 — 1874音乐西餐', heading_style))
story.append(Spacer(1, 0.3*cm))

time_badge2 = Table([[Paragraph('⏰ 11:30 - 13:30', time_style)]], colWidths=[4*cm])
time_badge2.setStyle(TableStyle([
    ('BACKGROUND', (0,0), (-1,-1), PINK),
    ('ALIGN', (0,0), (-1,-1), 'CENTER'),
    ('TOPPADDING', (0,0), (-1,-1), 6),
    ('BOTTOMPADDING', (0,0), (-1,-1), 6),
    ('ROUNDEDCORNERS', [10, 10, 10, 10]),
]))
story.append(time_badge2)
story.append(Spacer(1, 0.5*cm))

story.append(Paragraph('📍 地点：1874音乐西餐·观景餐厅（千灯湖环宇城店）', sub_heading_style))
story.append(Paragraph('"晚生百年又何妨，一朝相识终为伴" —— 1874音乐西餐以陈奕迅的《1874》为灵感，寓意不留遗憾地去爱。这是一家以西餐为主打，融合东南亚特色菜系的音乐连锁西餐厅，曾荣获"佛山十大招牌菜"荣誉。在优美的音乐氛围中，与心爱的TA共享精致美食，让味蕾与心灵同时被治愈。', body_style))
story.append(Spacer(1, 0.2*cm))

info_data2 = [
    ['地址', '南海区桂澜中路18号千灯湖环宇城4楼L427'],
    ['电话', '0757-86281874'],
    ['营业时间', '周一至周日 10:00-22:00'],
    ['人均消费', '约120-150元/人'],
    ['交通', '距千灯湖公园步行约10分钟'],
]
info_table2 = Table(info_data2, colWidths=[3*cm, 12*cm])
info_table2.setStyle(TableStyle([
    ('FONTNAME', (0,0), (-1,-1), 'SimHei'),
    ('FONTSIZE', (0,0), (-1,-1), 10),
    ('TEXTCOLOR', (0,0), (0,-1), white),
    ('TEXTCOLOR', (1,0), (1,-1), DARK_TEXT),
    ('BACKGROUND', (0,0), (0,-1), PURPLE),
    ('BACKGROUND', (1,0), (1,-1), LIGHT_PURPLE),
    ('ALIGN', (0,0), (0,-1), 'CENTER'),
    ('VALIGN', (0,0), (-1,-1), 'MIDDLE'),
    ('TOPPADDING', (0,0), (-1,-1), 6),
    ('BOTTOMPADDING', (0,0), (-1,-1), 6),
]))
story.append(info_table2)
story.append(Spacer(1, 0.3*cm))

story.append(Paragraph('🍽️ 推荐菜品：', sub_heading_style))
menu_data = [
    ['菜品', '特色描述', '参考价格'],
    ['BBQ酱烧厚切牛扒', '招牌牛扒，肉质鲜嫩多汁', '约88元'],
    ['JBS岩烧三角肥牛扒', '岩烧工艺锁住肉汁', '约98元'],
    ['火焰M7牛扒', '现场火焰表演，视觉+味觉双重享受', '约128元'],
    ['玫瑰花酸奶慕斯', '浪漫甜品，颜值与味道并存', '约38元'],
]
menu_table = Table(menu_data, colWidths=[5*cm, 7.5*cm, 3.5*cm])
menu_table.setStyle(TableStyle([
    ('FONTNAME', (0,0), (-1,-1), 'SimHei'),
    ('FONTSIZE', (0,0), (-1,-1), 10),
    ('BACKGROUND', (0,0), (-1,0), PINK),
    ('TEXTCOLOR', (0,0), (-1,0), white),
    ('TEXTCOLOR', (0,1), (-1,-1), DARK_TEXT),
    ('ALIGN', (0,0), (-1,-1), 'CENTER'),
    ('VALIGN', (0,0), (-1,-1), 'MIDDLE'),
    ('TOPPADDING', (0,0), (-1,-1), 8),
    ('BOTTOMPADDING', (0,0), (-1,-1), 8),
    ('ROWBACKGROUNDS', (0,1), (-1,-1), [SOFT_BG, white]),
    ('GRID', (0,0), (-1,-1), 0.5, LIGHT_PINK),
]))
story.append(menu_table)
story.append(Spacer(1, 0.2*cm))

story.append(Paragraph('💡 小贴士：', sub_heading_style))
tips2 = [
    '• 建议提前一天电话预约，特别是周末和节假日',
    '• 靠窗位置可以欣赏千灯湖美景，记得提前说明需求',
    '• 火焰M7牛扒有现场火焰表演，仪式感满满，推荐尝试',
    '• 用餐结束后可以在环宇城商场逛逛消食',
]
for tip in tips2:
    story.append(Paragraph(tip, tip_style))

story.append(PageBreak())

# ========== SECTION 4: AFTERNOON ==========
story.append(Paragraph('四、下午茶歇 — 怡海港爱情大道', heading_style))
story.append(Spacer(1, 0.3*cm))

time_badge3 = Table([[Paragraph('⏰ 14:00 - 16:00', time_style)]], colWidths=[4*cm])
time_badge3.setStyle(TableStyle([
    ('BACKGROUND', (0,0), (-1,-1), PINK),
    ('ALIGN', (0,0), (-1,-1), 'CENTER'),
    ('TOPPADDING', (0,0), (-1,-1), 6),
    ('BOTTOMPADDING', (0,0), (-1,-1), 6),
    ('ROUNDEDCORNERS', [10, 10, 10, 10]),
]))
story.append(time_badge3)
story.append(Spacer(1, 0.5*cm))

story.append(Paragraph('📍 地点：怡海港·爱情大道（南海区桂城街道）', sub_heading_style))
story.append(Paragraph('怡海港的"爱情大道"长达300米，宽12米，以红色灯柱装饰，每逢夜晚灯光亮起，浪漫氛围拉满。大道上还有甜蜜的"爱情桥"，你可以和TA买一把同心锁，写下心愿，在桃花灼灼之下见证浓浓爱意。下午时分阳光正好，沿着爱情大道慢慢散步，享受慵懒的午后时光。', body_style))
story.append(Spacer(1, 0.2*cm))

info_data3 = [
    ['地址', '佛山市南海区桂城街道怡海港'],
    ['门票', '免费开放'],
    ['建议时长', '约2小时'],
    ['特色', '爱情灯柱、同心锁、爱情桥'],
    ['推荐活动', '漫步爱情大道、挂同心锁、拍照留念'],
]
info_table3 = Table(info_data3, colWidths=[3*cm, 12*cm])
info_table3.setStyle(TableStyle([
    ('FONTNAME', (0,0), (-1,-1), 'SimHei'),
    ('FONTSIZE', (0,0), (-1,-1), 10),
    ('TEXTCOLOR', (0,0), (0,-1), white),
    ('TEXTCOLOR', (1,0), (1,-1), DARK_TEXT),
    ('BACKGROUND', (0,0), (0,-1), PURPLE),
    ('BACKGROUND', (1,0), (1,-1), LIGHT_PURPLE),
    ('ALIGN', (0,0), (0,-1), 'CENTER'),
    ('VALIGN', (0,0), (-1,-1), 'MIDDLE'),
    ('TOPPADDING', (0,0), (-1,-1), 6),
    ('BOTTOMPADDING', (0,0), (-1,-1), 6),
]))
story.append(info_table3)
story.append(Spacer(1, 0.3*cm))

story.append(Paragraph('💡 小贴士：', sub_heading_style))
tips3 = [
    '• 同心锁可以在附近的小店购买，价格不贵但意义非凡',
    '• 下午3-4点光线最柔和，非常适合拍情侣照',
    '• 爱情大道晚上灯光亮起后更加浪漫，如果时间允许可以傍晚再来',
    '• 附近有花艺工作室，可以为TA定制一束花',
]
for tip in tips3:
    story.append(Paragraph(tip, tip_style))

story.append(PageBreak())

# ========== SECTION 5: LATE AFTERNOON ==========
story.append(Paragraph('五、傍晚漫步 — 保利西街', heading_style))
story.append(Spacer(1, 0.3*cm))

time_badge4 = Table([[Paragraph('⏰ 16:30 - 18:00', time_style)]], colWidths=[4*cm])
time_badge4.setStyle(TableStyle([
    ('BACKGROUND', (0,0), (-1,-1), PINK),
    ('ALIGN', (0,0), (-1,-1), 'CENTER'),
    ('TOPPADDING', (0,0), (-1,-1), 6),
    ('BOTTOMPADDING', (0,0), (-1,-1), 6),
    ('ROUNDEDCORNERS', [10, 10, 10, 10]),
]))
story.append(time_badge4)
story.append(Spacer(1, 0.5*cm))

story.append(Paragraph('📍 地点：保利西街（南海区桂城街道）', sub_heading_style))
story.append(Paragraph('保利西街被誉为"南海最浪漫的风情街"，数百米的街道汇聚了大型商业体和各式中外食肆、酒吧。这里有佛山保利洲际酒店、杨家将新派海鲜、W堤岸酒吧、蒙塔娜咖啡厅（MONTANA）、恋爱季节花艺工作室、绘馆等。傍晚时分，夕阳西下，漫步在这条风情街上，品味咖啡，感受艺术氛围，与TA共享美好黄昏。', body_style))
story.append(Spacer(1, 0.2*cm))

info_data4 = [
    ['地址', '佛山市南海区桂城街道保利西街'],
    ['门票', '免费开放（部分店铺消费）'],
    ['建议时长', '约1.5小时'],
    ['交通', '步行可达，距怡海港约1公里'],
    ['推荐店铺', '蒙塔娜咖啡厅、恋爱季节花艺工作室'],
]
info_table4 = Table(info_data4, colWidths=[3*cm, 12*cm])
info_table4.setStyle(TableStyle([
    ('FONTNAME', (0,0), (-1,-1), 'SimHei'),
    ('FONTSIZE', (0,0), (-1,-1), 10),
    ('TEXTCOLOR', (0,0), (0,-1), white),
    ('TEXTCOLOR', (1,0), (1,-1), DARK_TEXT),
    ('BACKGROUND', (0,0), (0,-1), PURPLE),
    ('BACKGROUND', (1,0), (1,-1), LIGHT_PURPLE),
    ('ALIGN', (0,0), (0,-1), 'CENTER'),
    ('VALIGN', (0,0), (-1,-1), 'MIDDLE'),
    ('TOPPADDING', (0,0), (-1,-1), 6),
    ('BOTTOMPADDING', (0,0), (-1,-1), 6),
]))
story.append(info_table4)
story.append(Spacer(1, 0.3*cm))

story.append(Paragraph('💡 小贴士：', sub_heading_style))
tips4 = [
    '• 推荐在MONTANA蒙塔娜咖啡厅点杯咖啡，享受下午茶时光',
    '• 恋爱季节花艺工作室可以为TA定制一束鲜花，制造惊喜',
    '• 绘馆可以一起体验绘画，留下属于你们的艺术作品',
    '• 傍晚的夕阳搭配街道灯光，是拍照的黄金时段',
]
for tip in tips4:
    story.append(Paragraph(tip, tip_style))

story.append(PageBreak())

# ========== SECTION 6: DINNER ==========
story.append(Paragraph('六、晚餐时光 — 华希尔顿逸林酒店', heading_style))
story.append(Spacer(1, 0.3*cm))

time_badge5 = Table([[Paragraph('⏰ 18:30 - 20:30', time_style)]], colWidths=[4*cm])
time_badge5.setStyle(TableStyle([
    ('BACKGROUND', (0,0), (-1,-1), PINK),
    ('ALIGN', (0,0), (-1,-1), 'CENTER'),
    ('TOPPADDING', (0,0), (-1,-1), 6),
    ('BOTTOMPADDING', (0,0), (-1,-1), 6),
    ('ROUNDEDCORNERS', [10, 10, 10, 10]),
]))
story.append(time_badge5)
story.append(Spacer(1, 0.5*cm))

story.append(Paragraph('📍 地点：佛山南海和华希尔顿逸林酒店·天厨全日餐厅', sub_heading_style))
story.append(Paragraph('"美好的一天，有朝阳、玫瑰花，还有让人怦然心动的你。" 佛山南海和华希尔顿逸林酒店，许你一段浪漫夜晚与饕餮盛宴。云端餐厅——天厨全日餐厅，以曼妙的高空城市夜景搭配精致美食，让时光在此刻定格。陪伴是最长情的告白，美食是最浪漫的共享，和心中的TA在这里打造浪漫回忆。', body_style))
story.append(Spacer(1, 0.2*cm))

info_data5 = [
    ['地址', '南海区大沥镇广佛路盐步段和华商贸广场1座'],
    ['电话', '0757-86677777'],
    ['餐厅', '天厨全日餐厅（云端餐厅）'],
    ['人均消费', '约200-300元/人'],
    ['特色', '高空夜景、国际自助、精致服务'],
]
info_table5 = Table(info_data5, colWidths=[3*cm, 12*cm])
info_table5.setStyle(TableStyle([
    ('FONTNAME', (0,0), (-1,-1), 'SimHei'),
    ('FONTSIZE', (0,0), (-1,-1), 10),
    ('TEXTCOLOR', (0,0), (0,-1), white),
    ('TEXTCOLOR', (1,0), (1,-1), DARK_TEXT),
    ('BACKGROUND', (0,0), (0,-1), PURPLE),
    ('BACKGROUND', (1,0), (1,-1), LIGHT_PURPLE),
    ('ALIGN', (0,0), (0,-1), 'CENTER'),
    ('VALIGN', (0,0), (-1,-1), 'MIDDLE'),
    ('TOPPADDING', (0,0), (-1,-1), 6),
    ('BOTTOMPADDING', (0,0), (-1,-1), 6),
]))
story.append(info_table5)
story.append(Spacer(1, 0.3*cm))

story.append(Paragraph('💡 小贴士：', sub_heading_style))
tips5 = [
    '• 建议提前2-3天电话预约，说明是情侣约会，酒店可安排靠窗景观位',
    '• 可以提前联系酒店预订玫瑰花或巧克力，为TA制造惊喜',
    '• 日落时分到达餐厅，可以同时欣赏夕阳和夜景',
    '• 如果预算允许，可以考虑预订酒店客房，将浪漫延续到第二天',
]
for tip in tips5:
    story.append(Paragraph(tip, tip_style))

story.append(PageBreak())

# ========== SECTION 7: NIGHT ==========
story.append(Paragraph('七、夜晚浪漫 — 万达广场摩天轮', heading_style))
story.append(Spacer(1, 0.3*cm))

time_badge6 = Table([[Paragraph('⏰ 21:00 - 22:00', time_style)]], colWidths=[4*cm])
time_badge6.setStyle(TableStyle([
    ('BACKGROUND', (0,0), (-1,-1), PINK),
    ('ALIGN', (0,0), (-1,-1), 'CENTER'),
    ('TOPPADDING', (0,0), (-1,-1), 6),
    ('BOTTOMPADDING', (0,0), (-1,-1), 6),
    ('ROUNDEDCORNERS', [10, 10, 10, 10]),
]))
story.append(time_badge6)
story.append(Spacer(1, 0.5*cm))

story.append(Paragraph('📍 地点：大沥万达广场摩天轮', sub_heading_style))
story.append(Paragraph('有人说，在摩天轮到达最高处的时候许下的心愿都会实现；也有人说，摩天轮到达顶点的时候恋人亲吻，爱情可以长长久久。在大沥万达广场的摩天轮上，俯瞰城市璀璨灯火，在星空下与TA互诉衷肠，为这美好的一天画上圆满的句号。', body_style))
story.append(Spacer(1, 0.2*cm))

info_data6 = [
    ['地址', '佛山市南海区大沥镇万达广场'],
    ['门票', '约30-50元/人'],
    ['建议时长', '约1小时（含排队）'],
    ['最佳时间', '夜晚灯光亮起后'],
    ['特色', '城市夜景、高空许愿、浪漫亲吻'],
]
info_table6 = Table(info_data6, colWidths=[3*cm, 12*cm])
info_table6.setStyle(TableStyle([
    ('FONTNAME', (0,0), (-1,-1), 'SimHei'),
    ('FONTSIZE', (0,0), (-1,-1), 10),
    ('TEXTCOLOR', (0,0), (0,-1), white),
    ('TEXTCOLOR', (1,0), (1,-1), DARK_TEXT),
    ('BACKGROUND', (0,0), (0,-1), PURPLE),
    ('BACKGROUND', (1,0), (1,-1), LIGHT_PURPLE),
    ('ALIGN', (0,0), (0,-1), 'CENTER'),
    ('VALIGN', (0,0), (-1,-1), 'MIDDLE'),
    ('TOPPADDING', (0,0), (-1,-1), 6),
    ('BOTTOMPADDING', (0,0), (-1,-1), 6),
]))
story.append(info_table6)
story.append(Spacer(1, 0.3*cm))

story.append(Paragraph('💡 小贴士：', sub_heading_style))
tips6 = [
    '• 摩天轮到达最高点时记得许愿，据说愿望会成真哦！',
    '• 周末和节假日可能排队较久，建议工作日前往或提前到达',
    '• 万达广场内有电影院，如果摩天轮后还有精力可以看场电影',
    '• 夜晚温差较大，记得为TA准备一件外套，贴心加分',
]
for tip in tips6:
    story.append(Paragraph(tip, tip_style))

story.append(PageBreak())

# ========== SECTION 8: TIPS & ALTERNATIVES ==========
story.append(Paragraph('八、约会小贴士 & 备选方案', heading_style))
story.append(Spacer(1, 0.3*cm))

story.append(Paragraph('🌟 约会通用小贴士', sub_heading_style))
general_tips = [
    '【穿搭建议】建议选择舒适但有质感的穿搭，女生可穿连衣裙搭配小高跟，男生可穿休闲衬衫，既得体又方便行走。',
    '【天气关注】出发前查看天气预报，准备好雨伞或防晒用品。佛山夏季多雨，春秋最为宜人。',
    '【充电准备】手机充满电，带上充电宝，约会过程中需要导航、拍照、支付等，电量很重要。',
    '【提前预约】餐厅和酒店建议提前1-3天电话预约，特别是周末和节假日。',
    '【惊喜准备】可以提前准备小礼物，如花束、手工卡片或小饰品，在恰当的时机送给TA。',
    '【拍照攻略】千灯湖的湖光、爱情大道的灯柱、摩天轮的夜景都是绝佳拍照背景，记得多拍美照。',
]
for tip in general_tips:
    story.append(Paragraph(tip, tip_style))

story.append(Spacer(1, 0.3*cm))
story.append(Paragraph('🔄 备选方案', sub_heading_style))

alt_data = [
    ['备选地点', '特色', '适合场景'],
    ['梦里水乡景区', '五大园区，色彩斑斓的建筑，网红店聚集', '文艺情侣，喜欢拍照'],
    ['南海大湿地公园', '粉色沙滩，梦幻少女风', '追求浪漫仪式感'],
    ['一览读书驿站', 'Tiffany蓝落地玻璃，书海+奶茶', '安静内敛的文艺约会'],
    ['国艺影视城', '复古上海街，穿越时空的爱恋', '喜欢复古风格的情侣'],
]
alt_table = Table(alt_data, colWidths=[4*cm, 6.5*cm, 5*cm])
alt_table.setStyle(TableStyle([
    ('FONTNAME', (0,0), (-1,-1), 'SimHei'),
    ('FONTSIZE', (0,0), (-1,-1), 10),
    ('BACKGROUND', (0,0), (-1,0), PINK),
    ('TEXTCOLOR', (0,0), (-1,0), white),
    ('TEXTCOLOR', (0,1), (-1,-1), DARK_TEXT),
    ('ALIGN', (0,0), (-1,-1), 'CENTER'),
    ('VALIGN', (0,0), (-1,-1), 'MIDDLE'),
    ('TOPPADDING', (0,0), (-1,-1), 8),
    ('BOTTOMPADDING', (0,0), (-1,-1), 8),
    ('ROWBACKGROUNDS', (0,1), (-1,-1), [SOFT_BG, white]),
    ('GRID', (0,0), (-1,-1), 0.5, LIGHT_PINK),
]))
story.append(alt_table)

story.append(PageBreak())

# ========== SECTION 9: BUDGET ==========
story.append(Paragraph('九、交通与预算参考', heading_style))
story.append(Spacer(1, 0.3*cm))

story.append(Paragraph('💰 预算参考表', sub_heading_style))
budget_data = [
    ['项目', '明细', '预估费用'],
    ['交通', '自驾油费/停车费 或 地铁公交', '约30-80元'],
    ['上午', '千灯湖公园（免费）', '0元'],
    ['午餐', '1874音乐西餐（2人）', '约240-300元'],
    ['下午', '怡海港同心锁+小消费', '约50-100元'],
    ['傍晚', '保利西街咖啡/花束', '约80-150元'],
    ['晚餐', '希尔顿逸林酒店（2人）', '约400-600元'],
    ['夜晚', '万达广场摩天轮（2人）', '约60-100元'],
    ['其他', '零食、停车、临时消费', '约50-100元'],
    ['合计', '—', '约910-1430元'],
]
budget_table = Table(budget_data, colWidths=[3*cm, 8*cm, 4.5*cm])
budget_table.setStyle(TableStyle([
    ('FONTNAME', (0,0), (-1,-1), 'SimHei'),
    ('FONTSIZE', (0,0), (-1,-1), 10),
    ('BACKGROUND', (0,0), (-1,0), PINK),
    ('TEXTCOLOR', (0,0), (-1,0), white),
    ('BACKGROUND', (0,-1), (-1,-1), LIGHT_PINK),
    ('TEXTCOLOR', (0,-1), (-1,-1), PINK),
    ('FONTSIZE', (0,-1), (-1,-1), 12),
    ('TEXTCOLOR', (0,1), (-1,-2), DARK_TEXT),
    ('ALIGN', (0,0), (-1,-1), 'CENTER'),
    ('VALIGN', (0,0), (-1,-1), 'MIDDLE'),
    ('TOPPADDING', (0,0), (-1,-1), 8),
    ('BOTTOMPADDING', (0,0), (-1,-1), 8),
    ('ROWBACKGROUNDS', (0,1), (-1,-2), [SOFT_BG, white]),
    ('GRID', (0,0), (-1,-1), 0.5, LIGHT_PINK),
]))
story.append(budget_table)

story.append(Spacer(1, 0.5*cm))

story.append(Paragraph('🚗 交通参考', sub_heading_style))
traffic_tips = [
    '• 自驾：南海区各约会地点之间距离较近，停车便利，推荐自驾出行',
    '• 地铁：广佛线贯穿南海区主要区域，千灯湖站、𧒽岗站均可直达部分景点',
    '• 打车：各地点间打车费用约10-25元，方便快捷',
    '• 步行：千灯湖至环宇城、怡海港至保利西街均可步行到达，享受沿途风景',
]
for tip in traffic_tips:
    story.append(Paragraph(tip, tip_style))

story.append(Spacer(1, 1*cm))

# Closing message
story.append(Paragraph('—————————————————', body_style))
story.append(Spacer(1, 0.3*cm))

closing_style = ParagraphStyle('Closing', parent=styles['Normal'],
    fontName='SimHei', fontSize=14, textColor=PINK, alignment=TA_CENTER,
    leading=22)

story.append(Paragraph('愿这份约会计划能为你们的爱情增添美好回忆', closing_style))
story.append(Spacer(1, 0.3*cm))
story.append(Paragraph('<font size="40" color="#E91E63">♥</font>', body_style))

story.append(Spacer(1, 0.5*cm))
story.append(Paragraph('<para align="center"><font name="SimHei" size="10" color="#7B1FA2"><i>—— 爱在南海，浪漫每一天 ——</i></font></para>', body_style))

# Build PDF
doc.build(story)
print("PDF generated successfully: date_plan.pdf")
